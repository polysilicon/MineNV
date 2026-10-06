// osl-launch.exe: what Melty starts on Play.
//  1. finds the New Vegas forms the sheets name in the player's own FalloutNV.esm and writes
//     Data/NVSE/Plugins/osl_forms.ini for the plugin (log: osl-launch.log next to this program),
//  2. starts the bundled Minecraft (Prism Launcher, instance "OverworldSupplyLine") unless it is already up,
//  3. starts New Vegas through xNVSE (nvse_loader.exe).
//
// osl-launch.exe --fnv <New Vegas folder> --mc <Overworld Supply Line folder in LocalAppData> [--resolve-only]
package main

import (
	"flag"
	"fmt"
	"io"
	"log"
	"net"
	"os"
	"os/exec"
	"path/filepath"
	"sort"
	"strings"
	"time"
)

const (
	linkAddr     = "127.0.0.1:25599"
	instanceName = "OverworldSupplyLine"
)

func main() {
	fnv := flag.String("fnv", "", "Fallout: New Vegas folder")
	mc := flag.String("mc", "", "folder holding the bundled Prism Launcher")
	resolveOnly := flag.Bool("resolve-only", false, "only write osl_forms.ini")
	flag.Parse()

	exe, _ := os.Executable()
	logPath := filepath.Join(filepath.Dir(exe), "osl-launch.log")
	if f, err := os.Create(logPath); err == nil {
		log.SetOutput(io.MultiWriter(os.Stderr, f))
		defer f.Close()
	}
	log.SetFlags(log.Ltime)
	if *fnv == "" {
		fail("no --fnv folder given")
	}

	if err := writeForms(*fnv); err != nil {
		log.Printf("forms: %v (salvage, quest chests and daily items stay off until this works)", err)
	}
	if *resolveOnly {
		return
	}
	if *mc != "" {
		startMinecraft(*mc)
	}
	loader := filepath.Join(*fnv, "nvse_loader.exe")
	cmd := exec.Command(loader)
	cmd.Dir = *fnv
	if err := cmd.Start(); err != nil {
		fail("couldn't start " + loader + ": " + err.Error())
	}
	log.Printf("started %s (pid %d)", loader, cmd.Process.Pid)
}

func fail(msg string) {
	log.Print("error: " + msg)
	os.Exit(1)
}

func writeForms(fnv string) error {
	esmPath := filepath.Join(fnv, "Data", "FalloutNV.esm")
	f, err := os.Open(esmPath)
	if err != nil {
		return err
	}
	defer f.Close()
	found, ambiguous, err := resolveForms(f, wanted)
	if err != nil {
		return err
	}
	var lines []string
	for _, w := range wanted {
		id, ok := found[w.key()]
		switch {
		case !ok:
			log.Printf("forms: %s %q not found in FalloutNV.esm", w.Type, w.Name)
		case len(ambiguous[w.key()]) > 1:
			log.Printf("forms: %s %q matches %d records, using %08X", w.Type, w.Name, len(ambiguous[w.key()]), id)
		default:
			log.Printf("forms: %s %q = %08X", w.Type, w.Name, id)
		}
		if ok {
			lines = append(lines, fmt.Sprintf("%s=%08X", w.key(), id))
		}
	}
	sort.Strings(lines)
	out := filepath.Join(fnv, "Data", "NVSE", "Plugins", "osl_forms.ini")
	if err := os.MkdirAll(filepath.Dir(out), 0o755); err != nil {
		return err
	}
	content := "; written by osl-launch.exe from your FalloutNV.esm on every Play\n" + strings.Join(lines, "\n") + "\n"
	if err := os.WriteFile(out, []byte(content), 0o644); err != nil {
		return err
	}
	log.Printf("forms: %d of %d found, wrote %s", len(lines), len(wanted), out)
	return nil
}

func linkUp() bool {
	c, err := net.DialTimeout("tcp", linkAddr, 300*time.Millisecond)
	if err != nil {
		return false
	}
	c.Close()
	return true
}

// startMinecraft starts the bundled Prism instance unless Minecraft's link is already listening.
// New Vegas doesn't wait for it: the plugin links up whenever Minecraft is ready.
func startMinecraft(mcHome string) {
	if linkUp() {
		log.Print("minecraft: already running")
		return
	}
	prism := filepath.Join(mcHome, "Prism", "prismlauncher.exe")
	if _, err := os.Stat(prism); err != nil {
		log.Printf("minecraft: %s missing: %v", prism, err)
		return
	}
	cmd := exec.Command(prism, "--launch", instanceName)
	cmd.Dir = filepath.Dir(prism)
	if err := cmd.Start(); err != nil {
		log.Printf("minecraft: couldn't start Prism: %v", err)
		return
	}
	log.Printf("minecraft: started Prism (pid %d)", cmd.Process.Pid)
	cmd.Process.Release()
}
