package main

import (
	"bytes"
	"compress/zlib"
	"encoding/binary"
	"testing"
)

func sub(typ string, data []byte) []byte {
	b := []byte(typ)
	b = binary.LittleEndian.AppendUint16(b, uint16(len(data)))
	return append(b, data...)
}

func rec(typ string, id uint32, compressed bool, subs ...[]byte) []byte {
	body := bytes.Join(subs, nil)
	flags := uint32(0)
	if compressed {
		var z bytes.Buffer
		w := zlib.NewWriter(&z)
		w.Write(body)
		w.Close()
		body = append(binary.LittleEndian.AppendUint32(nil, uint32(len(body))), z.Bytes()...)
		flags = flagCompressed
	}
	h := []byte(typ)
	h = binary.LittleEndian.AppendUint32(h, uint32(len(body)))
	h = binary.LittleEndian.AppendUint32(h, flags)
	h = binary.LittleEndian.AppendUint32(h, id)
	h = append(h, make([]byte, 8)...)
	return append(h, body...)
}

func grup(label string, content ...[]byte) []byte {
	body := bytes.Join(content, nil)
	h := []byte("GRUP")
	h = binary.LittleEndian.AppendUint32(h, uint32(len(body)+headerSize))
	h = append(h, []byte(label)...)
	h = append(h, make([]byte, 12)...)
	return append(h, body...)
}

func z(s string) []byte { return append([]byte(s), 0) }

func TestResolveForms(t *testing.T) {
	esm := bytes.Join([][]byte{
		rec("TES4", 0, false, sub("HEDR", make([]byte, 12))),
		grup("WEAP", rec("WEAP", 0x1111, false, sub("EDID", z("Scrap")), sub("FULL", z("Scrap Metal")))),
		grup("MISC",
			rec("MISC", 0x31944, false, sub("EDID", z("ScrapMetal")), sub("FULL", z("Scrap Metal"))),
			rec("MISC", 0x2222, true, sub("EDID", z("TinCan")), sub("FULL", z("Tin Can"))),
			rec("MISC", 0x3333, false, sub("EDID", z("DuctTape01")), sub("FULL", z("Duct Tape"))),
			rec("MISC", 0x3334, false, sub("EDID", z("DuctTape02")), sub("FULL", z("Duct Tape")))),
		grup("QUST", rec("QUST", 0x104C1C, false, sub("EDID", z("VMS01")), sub("FULL", z("Ain't That a Kick in the Head")))),
	}, nil)
	want := []wantedForm{{"MISC", "Scrap Metal"}, {"MISC", "Tin Can"}, {"MISC", "Duct Tape"}, {"MISC", "Wonderglue"},
		{"QUST", "Ain't That a Kick in the Head"}}
	found, ambiguous, err := resolveForms(bytes.NewReader(esm), want)
	if err != nil {
		t.Fatal(err)
	}
	check := func(k string, id uint32) {
		if found[k] != id {
			t.Errorf("%s = %08X, want %08X", k, found[k], id)
		}
	}
	check("MISC|Scrap Metal", 0x31944) // the WEAP with the same name is ignored
	check("MISC|Tin Can", 0x2222)      // compressed record
	check("MISC|Duct Tape", 0x3333)
	check("QUST|Ain't That a Kick in the Head", 0x104C1C)
	if _, ok := found["MISC|Wonderglue"]; ok {
		t.Error("Wonderglue isn't in this ESM")
	}
	if len(ambiguous["MISC|Duct Tape"]) != 2 {
		t.Error("two Duct Tapes should be reported")
	}
}

func TestWantedCoversSheets(t *testing.T) {
	if len(wanted) == 0 {
		t.Fatal("sheets_gen.go is empty: run tools/gen.py")
	}
	seen := map[string]bool{}
	for _, w := range wanted {
		if seen[w.key()] {
			t.Errorf("duplicate %s", w.key())
		}
		seen[w.key()] = true
	}
}
