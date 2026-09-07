package com.flashforge.farm.modelrepo;

import com.flashforge.farm.modelrepo.moderation.LabelAggregator;
import com.flashforge.farm.modelrepo.moderation.ModeratorFeed;
import com.flashforge.farm.modelrepo.profile.UserProfile;
import com.flashforge.farm.modelrepo.safety.JsonValidator;
import com.flashforge.farm.modelrepo.safety.PathSanitizer;
import com.flashforge.farm.modelrepo.safety.SafeImage;
import com.flashforge.farm.modelrepo.verify.ModelVerifier;
import com.flashforge.farm.modelrepo.verify.Verdict;
import com.google.gson.JsonObject;

import org.junit.Test;
import static org.junit.Assert.*;

import java.io.File;
import java.io.FileOutputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

public class SafetyAuditTest {

    @Test
    public void testPathSanitizer() {
        assertEquals("benchy.stl", PathSanitizer.clean("benchy.stl"));
        assertEquals("my model.3mf", PathSanitizer.clean("my model.3mf"));
        try {
            PathSanitizer.clean("../evil.stl");
            fail("traversal");
        } catch (IllegalArgumentException expected) {
        }
        try {
            PathSanitizer.clean("a/b.stl");
            fail("separator");
        } catch (IllegalArgumentException expected) {
        }
        try {
            PathSanitizer.clean("evil\u202Emodel.stl");
            fail("bidi");
        } catch (IllegalArgumentException expected) {
        }
        try {
            PathSanitizer.clean("evil.exe");
            fail("ext");
        } catch (IllegalArgumentException expected) {
        }
        try {
            PathSanitizer.clean("con.stl");
            fail("reserved");
        } catch (IllegalArgumentException expected) {
        }
        try {
            PathSanitizer.clean("a.stl\u0000b");
            fail("null byte");
        } catch (IllegalArgumentException expected) {
        }
    }

    @Test
    public void testJsonValidator() {
        JsonObject o = JsonValidator.parseObject("{\"a\":1,\"b\":\"x\"}".getBytes());
        assertEquals(1, o.get("a").getAsInt());
        try {
            JsonValidator.parseObject(new byte[300 * 1024]);
            fail("oversize");
        } catch (IllegalArgumentException expected) {
        }
        StringBuilder deep = new StringBuilder();
        for (int i = 0; i < 30; i++) {
            deep.append("{\"k\":");
        }
        deep.append("1");
        for (int i = 0; i < 30; i++) {
            deep.append("}");
        }
        try {
            JsonValidator.parseObject(deep.toString());
            fail("deep");
        } catch (IllegalArgumentException expected) {
        }
        try {
            JsonValidator.parseObject("[1,2]");
            fail("non-object");
        } catch (IllegalArgumentException expected) {
        }
    }

    @Test
    public void testSafeImagePng() {
        byte[] png = new byte[24];
        png[0] = (byte) 0x89;
        png[1] = 'P';
        png[2] = 'N';
        png[3] = 'G';
        png[16] = 0;
        png[17] = 0;
        png[18] = 0x04;
        png[19] = 0x00;
        png[20] = 0;
        png[21] = 0;
        png[22] = 0x03;
        png[23] = 0x00;
        SafeImage.Dims d = SafeImage.dims(png);
        assertEquals(1024, d.w);
        assertEquals(768, d.h);
        png[18] = 0x7F;
        try {
            SafeImage.dims(png);
            fail("too big");
        } catch (IllegalArgumentException expected) {
        }
    }

    @Test
    public void testSafeImageJpeg() {
        byte[] jpg = new byte[]{(byte) 0xFF, (byte) 0xD8, (byte) 0xFF, (byte) 0xC0,
                0x00, 0x0B, 0x08, 0x01, 0x00, 0x02, 0x00, 0x01, 0x01, 0x01, 0x11, 0x00,
                0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00};
        SafeImage.Dims d = SafeImage.dims(jpg);
        assertEquals(512, d.w);
        assertEquals(256, d.h);
    }

    @Test
    public void testModeratorFeedRoundTrip() throws Exception {
        byte[] seed = Identity.generateSeed();
        String seedHex = Identity.hex(seed);
        String owner = Identity.hex(Identity.publicKey(seed));
        List<ModeratorFeed.Label> labels = new ArrayList<>();
        labels.add(new ModeratorFeed.Label(owner, "block", "spam", "", 123L));
        String feedJson = ModeratorFeed.sign(seedHex, 1L, "", labels);
        ModeratorFeed.Feed f = ModeratorFeed.verify(feedJson.getBytes("UTF-8"), 0);
        assertEquals(owner.toLowerCase(), f.owner);
        assertEquals(1, f.labels.size());
        assertEquals("block", f.labels.get(0).action);
        try {
            ModeratorFeed.verify(feedJson.getBytes("UTF-8"), 2L);
            fail("stale seq accepted");
        } catch (IllegalArgumentException expected) {
        }
        String tampered = feedJson.replace("spam", "ham!");
        try {
            ModeratorFeed.verify(tampered.getBytes("UTF-8"), 0);
            fail("tamper accepted");
        } catch (IllegalArgumentException expected) {
        }
    }

    @Test
    public void testLabelAggregatorPersonalWins() throws Exception {
        TrustStore trust = new TrustStore(new TrustStore.MemoryStorage());
        LabelAggregator agg = new LabelAggregator(trust);
        byte[] seed = Identity.generateSeed();
        String modSeed = Identity.hex(seed);
        String mod = Identity.hex(Identity.publicKey(seed));
        byte[] victim = Identity.publicKey(Identity.generateSeed());
        String victimHex = Identity.hex(victim);
        agg.subscribe(mod);
        List<ModeratorFeed.Label> labels = new ArrayList<>();
        labels.add(new ModeratorFeed.Label(victimHex, "block", "test", "", 1L));
        agg.updateFeed(ModeratorFeed.sign(modSeed, 0L, "", labels).getBytes("UTF-8"));
        assertTrue(agg.isBlocked(victimHex));
        assertFalse(agg.isBlocked(mod));
        try {
            agg.updateFeed(ModeratorFeed.sign(modSeed, 0L, "", labels).getBytes("UTF-8"));
            fail("replay accepted");
        } catch (IllegalArgumentException expected) {
        }
        agg.unsubscribe(mod);
        assertFalse(agg.isBlocked(victimHex));
        trust.setBlocked(victimHex, true);
        assertTrue(agg.isBlocked(victimHex));
    }

    @Test
    public void testUserProfile() {
        byte[] raw = ("{\"pubkey\":\"abcdef0123456789\",\"name\":\"Alice\","
                + "\"bio\":\"hi\",\"avatar\":\"" + repeat("ab", 32) + "\","
                + "\"links\":[\"https://example.com\"],\"updated\":5}").getBytes();
        UserProfile p = UserProfile.parse(raw, "ABCDEF0123456789");
        assertEquals("abcde", p.pubkey.substring(0, 5));
        assertTrue(UserProfile.displayLabel(p, "ABCDEF0123456789").startsWith("Alice · "));
        assertEquals("profile:abcdef0123456789", UserProfile.recordKey("ABCDEF0123456789"));
        try {
            UserProfile.parse(raw, "0000000000000000");
            fail("key mismatch");
        } catch (IllegalArgumentException expected) {
        }
        byte[] badLink = "{\"pubkey\":\"ab\",\"links\":[\"file:///etc/passwd\"]}".getBytes();
        try {
            UserProfile.parse(badLink, "ab");
            fail("bad link");
        } catch (IllegalArgumentException expected) {
        }
    }

    @Test
    public void testVerifierRejectsSpoofedExtension() throws Exception {
        File tmp = File.createTempFile("evil", ".stl");
        FileOutputStream fos = new FileOutputStream(tmp);
        try {
            fos.write(new byte[]{0x7F, 'E', 'L', 'F', 1});
        } finally {
            fos.close();
        }
        assertEquals(Verdict.Reason.UNSAFE_TYPE,
                ModelVerifier.verifyForPublish(tmp).reason);
        tmp.delete();
    }

    @Test
    public void testVerifierDownloadFlow() throws Exception {
        File tmp = File.createTempFile("good", ".stl");
        FileOutputStream fos = new FileOutputStream(tmp);
        try {
            fos.write("solid test\nendsolid test\n".getBytes("UTF-8"));
        } finally {
            fos.close();
        }
        ModelMetadata meta = new ModelMetadata();
        meta.title = "Test";
        meta.designer.pubkey = Identity.hex(Identity.publicKey(Identity.generateSeed()));
        meta.files = new ArrayList<>(Arrays.asList(tmp.getName()));
        List<ModelVerifier.LocalFile> files = new ArrayList<>();
        files.add(new ModelVerifier.LocalFile(tmp, ""));
        TrustStore trust = new TrustStore(new TrustStore.MemoryStorage());
        LabelAggregator agg = new LabelAggregator(trust);
        Verdict v = ModelVerifier.verifyDownload(meta, files, trust, agg);
        assertTrue(v.allow);
        assertTrue(v.needsConfirm);
        assertEquals(Verdict.Reason.UNTRUSTED_PUBLISHER, v.reason);
        tmp.delete();
    }

    @Test
    public void testSyncResult() {
        SyncResult r = SyncResult.parse(
                "{\"new_models\":2,\"model_tickets\":[\"a\",\"b\"],\"profile_tickets\":[\"p\"]}");
        assertEquals(2, r.newModels);
        assertEquals(2, r.modelTickets.size());
        assertEquals(1, r.profileTickets.size());
        SyncResult empty = SyncResult.parse("not json");
        assertEquals(0, empty.newModels);
        assertTrue(empty.modelTickets.isEmpty());
        SyncResult legacy = SyncResult.parse("{\"new_models\":1,\"model_tickets\":[\"a\"]}");
        assertTrue(legacy.profileTickets.isEmpty());
    }

    private static String repeat(String s, int n) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < n; i++) {
            sb.append(s);
        }
        return sb.toString();
    }
}