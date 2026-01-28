package com.miniim.gateway.session;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class WsRouteStoreTest {

    @Test
    void parse_ShouldReturnNull_WhenBlank() {
        assertNull(WsRouteStore.parse(null));
        assertNull(WsRouteStore.parse(""));
        assertNull(WsRouteStore.parse("   "));
    }

    @Test
    void parse_ShouldSupportLegacyServerIdOnly() {
        WsRouteStore.RouteInfo info = WsRouteStore.parse("gw-a");
        assertNotNull(info);
        assertEquals("gw-a", info.serverId());
        assertNull(info.connId());
    }

    @Test
    void parse_ShouldSplitServerIdAndConnId() {
        WsRouteStore.RouteInfo info = WsRouteStore.parse("gw-a|cid123");
        assertNotNull(info);
        assertEquals("gw-a", info.serverId());
        assertEquals("cid123", info.connId());
    }

    @Test
    void format_ShouldJoinWithPipe() {
        assertEquals("s|c", WsRouteStore.format("s", "c"));
    }
}
