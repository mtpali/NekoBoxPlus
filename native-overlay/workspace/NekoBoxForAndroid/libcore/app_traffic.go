package libcore

import (
	"context"
	"libcore/appstats"
	"net"
	"sync/atomic"

	"github.com/sagernet/sing-box/adapter"
	C "github.com/sagernet/sing-box/constant"
	"github.com/sagernet/sing-tun"
	"github.com/sagernet/sing/common/bufio"
	N "github.com/sagernet/sing/common/network"
)

type appTrafficTracker struct {
	store *appstats.Store
	outbound adapter.OutboundManager
}

func (t *appTrafficTracker) counter(metadata adapter.InboundContext, outbound adapter.Outbound) *appstats.Counter {
	// Resolve selector/url-test groups once when routing, never for every packet.
	for depth := 0; depth < 16 && outbound != nil; depth++ {
		group, ok := outbound.(adapter.OutboundGroup)
		if !ok { break }
		next, exists := t.outbound.Outbound(group.Now())
		if !exists { break }
		outbound = next
	}
	if outbound == nil || outbound.Type() == C.TypeDirect || outbound.Type() == C.TypeDNS || outbound.Type() == C.TypeBlock { return nil }
	uid := int32(-1)
	if metadata.ProcessInfo != nil { uid = metadata.ProcessInfo.UserId }
	return t.store.Counter(uid)
}

func (t *appTrafficTracker) RoutedConnection(ctx context.Context, conn net.Conn, metadata adapter.InboundContext, rule adapter.Rule, outbound adapter.Outbound) net.Conn {
	counter := t.counter(metadata, outbound)
	if counter == nil { return conn }
	return bufio.NewInt64CounterConn(conn, []*atomic.Int64{&counter.Upload}, []*atomic.Int64{&counter.Download})
}

func (t *appTrafficTracker) RoutedPacketConnection(ctx context.Context, conn N.PacketConn, metadata adapter.InboundContext, rule adapter.Rule, outbound adapter.Outbound) N.PacketConn {
	counter := t.counter(metadata, outbound)
	if counter == nil { return conn }
	return bufio.NewInt64CounterPacketConn(conn, []*atomic.Int64{&counter.Upload}, nil, []*atomic.Int64{&counter.Download}, nil)
}

func (t *appTrafficTracker) RoutedFlow(ctx context.Context, metadata adapter.InboundContext, rule adapter.Rule, outbound adapter.Outbound) tun.FlowTracker {
	counter := t.counter(metadata, outbound)
	if counter == nil { return nil }
	return &appTrafficFlow{counter: counter}
}

type appTrafficFlow struct { counter *appstats.Counter }
func (f *appTrafficFlow) AttachFlow(tun.FlowHandle) {}
func (f *appTrafficFlow) CountForward(n int) { if n > 0 { f.counter.Upload.Add(int64(n)) } }
func (f *appTrafficFlow) CountReverse(n int) { if n > 0 { f.counter.Download.Add(int64(n)) } }
func (f *appTrafficFlow) FlowEstablished() {}
func (f *appTrafficFlow) CloseFlow(tun.FlowCloseReason) {}

// Enabled by the developer log option, before this instance starts routing.
func (b *BoxInstance) EnableAppTrafficStats() {
	b.access.Lock()
	defer b.access.Unlock()
	if b.forTest || b.appTraffic != nil || b.Box == nil { return }
	b.appTraffic = &appTrafficTracker{store: appstats.New(), outbound: b.Box.Outbound()}
	b.Box.Router().AppendTracker(b.appTraffic)
}

func (b *BoxInstance) QueryAppTrafficStats() string {
	b.access.Lock()
	tracker := b.appTraffic
	b.access.Unlock()
	if tracker == nil { return "[]" }
	return tracker.store.JSON()
}
