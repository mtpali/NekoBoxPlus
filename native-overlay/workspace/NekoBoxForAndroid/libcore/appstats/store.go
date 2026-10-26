package appstats

import (
	"encoding/json"
	"sort"
	"sync"
	"sync/atomic"
)

const MaxUIDs = 4096

type Counter struct {
	Upload   atomic.Int64
	Download atomic.Int64
}

type Row struct {
	UID      int32 `json:"uid"`
	Upload   int64 `json:"upload"`
	Download int64 `json:"download"`
}

// A lookup occurs once per connection. The packet path only increments two atomics.
// Counters survive connection closure; no URL, connection history or packet is retained.
type Store struct {
	mu       sync.Mutex
	counters map[int32]*Counter
}

func New() *Store { return &Store{counters: make(map[int32]*Counter)} }

func (s *Store) Counter(uid int32) *Counter {
	if uid < 0 {
		uid = -1
	}
	s.mu.Lock()
	defer s.mu.Unlock()
	if counter := s.counters[uid]; counter != nil {
		return counter
	}
	if len(s.counters) >= MaxUIDs && uid != -1 {
		uid = -1
		if counter := s.counters[uid]; counter != nil {
			return counter
		}
	}
	counter := new(Counter)
	s.counters[uid] = counter
	return counter
}

func (s *Store) Rows() []Row {
	s.mu.Lock()
	rows := make([]Row, 0, len(s.counters))
	for uid, counter := range s.counters {
		up, down := counter.Upload.Load(), counter.Download.Load()
		if up != 0 || down != 0 {
			rows = append(rows, Row{UID: uid, Upload: up, Download: down})
		}
	}
	s.mu.Unlock()
	sort.Slice(rows, func(i, j int) bool {
		a, b := uint64(rows[i].Upload)+uint64(rows[i].Download), uint64(rows[j].Upload)+uint64(rows[j].Download)
		if a == b { return rows[i].UID < rows[j].UID }
		return a > b
	})
	return rows
}

func (s *Store) JSON() string {
	data, _ := json.Marshal(s.Rows())
	return string(data)
}
