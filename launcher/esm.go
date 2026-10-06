package main

// Reads the player's own FalloutNV.esm to find the forms the sheets name (by record type and in-game name),
// so no form ID is guessed. Record layout: 24-byte record/GRUP headers, zlib-compressed bodies when flagged,
// subrecords with 2-byte sizes (XXXX for bigger ones). Based on CS2 in the Mojave's esp.go.

import (
	"bytes"
	"compress/zlib"
	"encoding/binary"
	"errors"
	"fmt"
	"io"
	"strings"
)

const (
	flagCompressed = 0x00040000
	headerSize     = 24
)

type wantedForm struct {
	Type string
	Name string
}

func (w wantedForm) key() string { return w.Type + "|" + w.Name }

// resolveForms scans the ESM once and returns, for each wanted form, its form ID. A name that matches more than
// one record of the type is reported (the first one by EditorID order wins only when the EditorID equals the name
// without spaces), so ambiguity is visible in the log.
func resolveForms(r io.ReadSeeker, wanted []wantedForm) (map[string]uint32, map[string][]uint32, error) {
	byType := map[string]map[string]bool{}
	for _, w := range wanted {
		if byType[w.Type] == nil {
			byType[w.Type] = map[string]bool{}
		}
		byType[w.Type][strings.ToLower(w.Name)] = true
	}
	matches := map[string][]uint32{}
	hdr := make([]byte, headerSize)
	for {
		if _, err := io.ReadFull(r, hdr); err != nil {
			if errors.Is(err, io.EOF) {
				break
			}
			return nil, nil, err
		}
		sig := string(hdr[0:4])
		size := binary.LittleEndian.Uint32(hdr[4:8])
		if sig == "GRUP" {
			label := string(hdr[8:12])
			gtype := int32(binary.LittleEndian.Uint32(hdr[12:16]))
			if gtype == 0 && byType[label] == nil {
				if _, err := r.Seek(int64(size)-headerSize, io.SeekCurrent); err != nil {
					return nil, nil, err
				}
			}
			continue
		}
		names := byType[sig]
		if names == nil {
			if _, err := r.Seek(int64(size), io.SeekCurrent); err != nil {
				return nil, nil, err
			}
			continue
		}
		body := make([]byte, size)
		if _, err := io.ReadFull(r, body); err != nil {
			return nil, nil, err
		}
		formID := binary.LittleEndian.Uint32(hdr[12:16])
		flags := binary.LittleEndian.Uint32(hdr[8:12])
		full, err := fullName(body, flags)
		if err != nil {
			return nil, nil, fmt.Errorf("record %s %08X: %w", sig, formID, err)
		}
		if names[strings.ToLower(full)] {
			k := sig + "|" + strings.ToLower(full)
			matches[k] = append(matches[k], formID)
		}
	}
	found := map[string]uint32{}
	ambiguous := map[string][]uint32{}
	for _, w := range wanted {
		ids := matches[w.Type+"|"+strings.ToLower(w.Name)]
		if len(ids) > 0 {
			found[w.key()] = ids[0]
		}
		if len(ids) > 1 {
			ambiguous[w.key()] = ids
		}
	}
	return found, ambiguous, nil
}

// fullName returns the record's FULL (in-game name) subrecord, or "".
func fullName(body []byte, flags uint32) (string, error) {
	if flags&flagCompressed != 0 {
		if len(body) < 4 {
			return "", errors.New("compressed record too short")
		}
		zr, err := zlib.NewReader(bytes.NewReader(body[4:]))
		if err != nil {
			return "", err
		}
		plain, err := io.ReadAll(zr)
		if err != nil {
			return "", err
		}
		body = plain
	}
	var bigSize uint32
	for p := 0; p+6 <= len(body); {
		typ := string(body[p : p+4])
		n := uint32(binary.LittleEndian.Uint16(body[p+4 : p+6]))
		p += 6
		if typ == "XXXX" {
			if p+4 > len(body) {
				return "", errors.New("truncated XXXX")
			}
			bigSize = binary.LittleEndian.Uint32(body[p : p+4])
			p += int(n)
			continue
		}
		if bigSize != 0 {
			n, bigSize = bigSize, 0
		}
		if p+int(n) > len(body) {
			return "", fmt.Errorf("subrecord %s overruns record", typ)
		}
		if typ == "FULL" {
			return strings.TrimRight(string(body[p:p+int(n)]), "\x00"), nil
		}
		p += int(n)
	}
	return "", nil
}
