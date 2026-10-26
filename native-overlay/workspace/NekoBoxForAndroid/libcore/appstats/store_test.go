package appstats

import (
	"encoding/json"
	"sync"
	"testing"
)

func TestCountsActiveAndClosedConnectionsWithoutKeepingTheirHistory(t *testing.T) {
	s := New()
	first := s.Counter(10001)
	first.Upload.Add(9)
	first.Download.Add(31)
	// A later connection for the same app retains all previous bytes.
	second := s.Counter(10001)
	if first != second { t.Fatal("per-connection allocation or lost app counter") }
	second.Download.Add(60)
	s.Counter(10002).Download.Add(110)
	rows := s.Rows()
	if len(rows) != 2 || rows[0].UID != 10002 || rows[1].Upload != 9 || rows[1].Download != 91 { t.Fatal(rows) }
}

func TestConcurrentTrafficNeverLosesOrDuplicatesBytes(t *testing.T) {
	s := New()
	var jobs sync.WaitGroup
	for worker := 0; worker < 12; worker++ {
		jobs.Add(1)
		go func() {
			defer jobs.Done()
			counter := s.Counter(10001)
			for packet := 0; packet < 10000; packet++ { counter.Upload.Add(3); counter.Download.Add(7) }
		}()
	}
	jobs.Wait()
	row := s.Rows()[0]
	if row.Upload != 360000 || row.Download != 840000 { t.Fatal(row) }
}

func TestUnknownUIDAndBoundedOverflowConserveTraffic(t *testing.T) {
	s := New()
	for uid := int32(1); uid <= MaxUIDs+100; uid++ { s.Counter(uid).Upload.Add(1) }
	s.Counter(-100).Download.Add(17)
	var up, down int64
	for _, row := range s.Rows() { up += row.Upload; down += row.Download }
	if len(s.counters) > MaxUIDs+1 || up != MaxUIDs+100 || down != 17 { t.Fatalf("%d %d %d",len(s.counters),up,down) }
}

func TestSnapshotIsNonDestructiveAndEmptySnapshotIsAnArray(t *testing.T) {
	s := New()
	if s.JSON() != "[]" { t.Fatal(s.JSON()) }
	s.Counter(20).Upload.Add(42)
	if s.JSON() != s.JSON() { t.Fatal("snapshot reset counters") }
	var rows []Row
	if err := json.Unmarshal([]byte(s.JSON()), &rows); err != nil || rows[0].Upload != 42 { t.Fatal(err,rows) }
}
