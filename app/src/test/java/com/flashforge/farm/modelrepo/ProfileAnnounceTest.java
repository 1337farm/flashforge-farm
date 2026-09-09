package com.flashforge.farm.modelrepo;

import com.flashforge.farm.modelrepo.profile.UserProfile;
import org.junit.Test;
import static org.junit.Assert.*;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

public class ProfileAnnounceTest {

    @Test
    public void profileJsonRoundTrip() {
        String pubkey = "d75a980182b10ab7d54bfed3c964073a0ee172f3daa62325af021a68f707511a";
        byte[] raw = UserProfile.toJson(pubkey, "alice", "bio", "", Arrays.asList("https://example.com"), 1700000000L);
        UserProfile p = UserProfile.parse(raw, pubkey);
        assertEquals(pubkey, p.pubkey);
        assertEquals("alice", p.name);
        assertEquals("bio", p.bio);
        assertEquals(1, p.links.size());
        assertEquals("profile:" + pubkey, UserProfile.recordKey(pubkey));
    }

    @Test
    public void profileKeyMismatchRejected() {
        String pubkey = "d75a980182b10ab7d54bfed3c964073a0ee172f3daa62325af021a68f707511a";
        byte[] raw = UserProfile.toJson(pubkey, "alice", "", "", null, 0L);
        try {
            UserProfile.parse(raw, "b75a980182b10ab7d54bfed3c964073a0ee172f3daa62325af021a68f707511a");
            fail("expected key mismatch");
        } catch (IllegalArgumentException expected) {
        }
    }

    @Test
    public void announceEnvelopeRoundTrip() {
        String env = SyncResult.announceEnvelope("engine-ticket-1", "profile-ticket-2");
        SyncResult.AnnouncePayload p = SyncResult.parseAnnounce(env);
        assertTrue(p.enveloped);
        assertEquals("engine-ticket-1", p.announceTicket);
        assertEquals("profile-ticket-2", p.profileTicket);
    }

    @Test
    public void legacyTicketPassesThrough() {
        SyncResult.AnnouncePayload p = SyncResult.parseAnnounce("blobticketABC");
        assertFalse(p.enveloped);
        assertEquals("blobticketABC", p.announceTicket);
    }

    @Test
    public void includingProfilesMergesWithoutDupes() {
        SyncResult base = SyncResult.parse("{\"new_models\":1,\"model_tickets\":[\"m\"],\"profile_tickets\":[\"p1\"]}");
        List<String> extra = new ArrayList<>();
        extra.add("p1");
        extra.add("p2");
        SyncResult merged = base.includingProfiles(extra);
        assertEquals(2, merged.profileTickets.size());
        assertTrue(merged.profileTickets.contains("p2"));
        assertEquals(1, merged.newModels);
    }
}
