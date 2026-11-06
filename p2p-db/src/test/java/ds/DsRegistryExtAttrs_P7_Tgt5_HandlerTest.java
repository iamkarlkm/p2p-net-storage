package ds;

import com.q3lives.ds.registry.DsRegistry;

import java.io.File;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import javax.net.p2p.api.P2PCommand;
import javax.net.p2p.model.ExtAttrsLastStringAck;
import javax.net.p2p.model.ExtAttrsListStringAck;
import javax.net.p2p.model.ExtAttrsWriteAck;
import javax.net.p2p.model.ExtaAddCommentRequest;
import javax.net.p2p.model.ExtaAddDescRequest;
import javax.net.p2p.model.ExtaAddSpecRequest;
import javax.net.p2p.model.ExtaAddTagRequest;
import javax.net.p2p.model.ExtaAddTagsRequest;
import javax.net.p2p.model.ExtaHasTagRequest;
import javax.net.p2p.model.ExtaLastCommentRequest;
import javax.net.p2p.model.ExtaLastDescRequest;
import javax.net.p2p.model.ExtaLastSpecRequest;
import javax.net.p2p.model.ExtaListCommentsRequest;
import javax.net.p2p.model.ExtaListDescsRequest;
import javax.net.p2p.model.ExtaListSpecsRequest;
import javax.net.p2p.model.ExtaListTagsFilteredRequest;
import javax.net.p2p.model.ExtaListTagsRequest;
import javax.net.p2p.model.ExtaRemoveAllCommentsRequest;
import javax.net.p2p.model.ExtaRemoveAllDescsRequest;
import javax.net.p2p.model.ExtaRemoveAllSpecsRequest;
import javax.net.p2p.model.ExtaRemoveAllTagsRequest;
import javax.net.p2p.model.ExtaRemoveLastCommentRequest;
import javax.net.p2p.model.ExtaRemoveLastDescRequest;
import javax.net.p2p.model.ExtaRemoveLastSpecRequest;
import javax.net.p2p.model.ExtaRemoveTagRequest;
import javax.net.p2p.model.ExtaRemoveTagsRequest;
import javax.net.p2p.model.P2PWrapper;
import javax.net.p2p.server.handler.ExtaAddCommentServerHandler;
import javax.net.p2p.server.handler.ExtaAddDescServerHandler;
import javax.net.p2p.server.handler.ExtaAddSpecServerHandler;
import javax.net.p2p.server.handler.ExtaAddTagServerHandler;
import javax.net.p2p.server.handler.ExtaAddTagsServerHandler;
import javax.net.p2p.server.handler.ExtaHasTagServerHandler;
import javax.net.p2p.server.handler.ExtaLastCommentServerHandler;
import javax.net.p2p.server.handler.ExtaLastDescServerHandler;
import javax.net.p2p.server.handler.ExtaLastSpecServerHandler;
import javax.net.p2p.server.handler.ExtaListCommentsServerHandler;
import javax.net.p2p.server.handler.ExtaListDescsServerHandler;
import javax.net.p2p.server.handler.ExtaListSpecsServerHandler;
import javax.net.p2p.server.handler.ExtaListTagsFilteredServerHandler;
import javax.net.p2p.server.handler.ExtaListTagsServerHandler;
import javax.net.p2p.server.handler.ExtaRemoveAllCommentsServerHandler;
import javax.net.p2p.server.handler.ExtaRemoveAllDescsServerHandler;
import javax.net.p2p.server.handler.ExtaRemoveAllSpecsServerHandler;
import javax.net.p2p.server.handler.ExtaRemoveAllTagsServerHandler;
import javax.net.p2p.server.handler.ExtaRemoveLastCommentServerHandler;
import javax.net.p2p.server.handler.ExtaRemoveLastDescServerHandler;
import javax.net.p2p.server.handler.ExtaRemoveLastSpecServerHandler;
import javax.net.p2p.server.handler.ExtaRemoveTagServerHandler;
import javax.net.p2p.server.handler.ExtaRemoveTagsServerHandler;
import javax.net.p2p.server.handler.Tgt3RegistryResolver;

public class DsRegistryExtAttrs_P7_Tgt5_HandlerTest {

    static final int SA = 12, PA = 0, SB = 42, PB = 0;

    private static File tmpDir(String prefix) throws Exception {
        File f = Files.createTempDirectory(prefix).toFile();
        f.deleteOnExit();
        return f;
    }

    @AfterEach
    void clear() {
        Tgt3RegistryResolver.clearForTest();
    }

    @Test
    void T01_TAG_AddRemoveIdempotent_List_Has_ok() throws Exception {
        File tmpA = tmpDir("dsreg_tgt5_A1_");
        File tmpB = tmpDir("dsreg_tgt5_B1_");
        try (DsRegistry rA = new DsRegistry(new File(tmpA, SA + "_" + PA).getAbsolutePath(), SA, PA);
             DsRegistry rB = new DsRegistry(new File(tmpB, SB + "_" + PB).getAbsolutePath(), SB, PB)) {
            Tgt3RegistryResolver.injectForTest(SA, PA, rA);
            Tgt3RegistryResolver.injectForTest(SB, PB, rB);
            String entryKey = "/apps/et1";

            ExtaAddTagRequest a1 = new ExtaAddTagRequest(SA, PA, SB, PB, 0L, entryKey, "red");
            ExtAttrsWriteAck w1 = (ExtAttrsWriteAck) new ExtaAddTagServerHandler().process(
                    P2PWrapper.build(1, P2PCommand.EXTA_ADD_TAG, a1)).getData();
            Assertions.assertTrue(w1.ok, "addTag red ok");
            Assertions.assertEquals(1L, w1.numericValue, "red add 1");

            ExtaAddTagRequest a2 = new ExtaAddTagRequest(SA, PA, SB, PB, 0L, entryKey, "red");
            ExtAttrsWriteAck w2 = (ExtAttrsWriteAck) new ExtaAddTagServerHandler().process(
                    P2PWrapper.build(2, P2PCommand.EXTA_ADD_TAG, a2)).getData();
            Assertions.assertFalse(w2.ok, "addTag red 第二次幂等 return false");
            Assertions.assertEquals(0L, w2.numericValue);

            ExtaAddTagRequest a3 = new ExtaAddTagRequest(SA, PA, SB, PB, 0L, entryKey, "blue");
            ExtaAddTagRequest a4 = new ExtaAddTagRequest(SA, PA, SB, PB, 0L, entryKey, "green");
            new ExtaAddTagServerHandler().process(P2PWrapper.build(3, P2PCommand.EXTA_ADD_TAG, a3));
            new ExtaAddTagServerHandler().process(P2PWrapper.build(4, P2PCommand.EXTA_ADD_TAG, a4));

            ExtaHasTagRequest h1 = new ExtaHasTagRequest(SA, PA, SB, PB, 0L, entryKey, "red");
            ExtAttrsWriteAck hA1 = (ExtAttrsWriteAck) new ExtaHasTagServerHandler().process(
                    P2PWrapper.build(5, P2PCommand.EXTA_HAS_TAG, h1)).getData();
            Assertions.assertEquals(1L, hA1.numericValue, "hasTag red=1");

            ExtaHasTagRequest h2 = new ExtaHasTagRequest(SA, PA, SB, PB, 0L, entryKey, "nonexistent");
            ExtAttrsWriteAck hA2 = (ExtAttrsWriteAck) new ExtaHasTagServerHandler().process(
                    P2PWrapper.build(6, P2PCommand.EXTA_HAS_TAG, h2)).getData();
            Assertions.assertEquals(0L, hA2.numericValue, "hasTag notExist=0");

            ExtaListTagsRequest ls = new ExtaListTagsRequest(SA, PA, SB, PB, 0L, entryKey);
            ExtAttrsListStringAck lA = (ExtAttrsListStringAck) new ExtaListTagsServerHandler().process(
                    P2PWrapper.build(7, P2PCommand.EXTA_LIST_TAGS, ls)).getData();
            Set<String> tags = new HashSet<>(lA.values);
            Assertions.assertEquals(3, tags.size(), "3 tags");
            Assertions.assertTrue(tags.contains("red") && tags.contains("blue") && tags.contains("green"));

            ExtaRemoveTagRequest r1 = new ExtaRemoveTagRequest(SA, PA, SB, PB, 0L, entryKey, "blue");
            ExtAttrsWriteAck rA1 = (ExtAttrsWriteAck) new ExtaRemoveTagServerHandler().process(
                    P2PWrapper.build(8, P2PCommand.EXTA_REMOVE_TAG, r1)).getData();
            Assertions.assertEquals(1L, rA1.numericValue, "removeTag blue success=1");

            ExtaRemoveTagRequest r2 = new ExtaRemoveTagRequest(SA, PA, SB, PB, 0L, entryKey, "blue");
            ExtAttrsWriteAck rA2 = (ExtAttrsWriteAck) new ExtaRemoveTagServerHandler().process(
                    P2PWrapper.build(9, P2PCommand.EXTA_REMOVE_TAG, r2)).getData();
            Assertions.assertEquals(0L, rA2.numericValue, "removeTag blue 第二次=0幂等");
        }
    }

    @Test
    void T02_TAG_AddRemoveTags_Batch_RemoveAll_EmptyList_ok() throws Exception {
        File tmpA = tmpDir("dsreg_tgt5_A2_");
        File tmpB = tmpDir("dsreg_tgt5_B2_");
        try (DsRegistry rA = new DsRegistry(new File(tmpA, SA + "_" + PA).getAbsolutePath(), SA, PA);
             DsRegistry rB = new DsRegistry(new File(tmpB, SB + "_" + PB).getAbsolutePath(), SB, PB)) {
            Tgt3RegistryResolver.injectForTest(SA, PA, rA);
            Tgt3RegistryResolver.injectForTest(SB, PB, rB);
            String entryKey = "/apps/et2";

            List<String> addL = Arrays.asList("apple", "banana", "cat", "dog", "egg");
            ExtaAddTagsRequest at = new ExtaAddTagsRequest(SA, PA, SB, PB, 0L, entryKey, new ArrayList<>(addL));
            ExtAttrsWriteAck w = (ExtAttrsWriteAck) new ExtaAddTagsServerHandler().process(
                    P2PWrapper.build(1, P2PCommand.EXTA_ADD_TAGS, at)).getData();
            Assertions.assertTrue(w.ok, "addTags 5条 anyAdded=true");

            ExtaListTagsFilteredRequest lf = new ExtaListTagsFilteredRequest(SA, PA, SB, PB, 0L,
                    entryKey, 100, "a");
            ExtAttrsListStringAck lA = (ExtAttrsListStringAck) new ExtaListTagsFilteredServerHandler().process(
                    P2PWrapper.build(2, P2PCommand.EXTA_LIST_TAGS_FILTERED, lf)).getData();
            Set<String> s = new HashSet<>(lA.values);
            Assertions.assertTrue(s.contains("apple"), "startsWith a apple");
            Assertions.assertFalse(s.contains("banana"), "no banana");

            ExtaListTagsFilteredRequest lf2 = new ExtaListTagsFilteredRequest(SA, PA, SB, PB, 0L,
                    entryKey, 2, null);
            ExtAttrsListStringAck lA2 = (ExtAttrsListStringAck) new ExtaListTagsFilteredServerHandler().process(
                    P2PWrapper.build(3, P2PCommand.EXTA_LIST_TAGS_FILTERED, lf2)).getData();
            Assertions.assertTrue(lA2.values.size() <= 2, "limit=2 max2");

            ExtaRemoveTagsRequest rt = new ExtaRemoveTagsRequest(SA, PA, SB, PB, 0L, entryKey,
                    new ArrayList<>(Arrays.asList("cat", "dog", "nonexist")));
            ExtAttrsWriteAck w2 = (ExtAttrsWriteAck) new ExtaRemoveTagsServerHandler().process(
                    P2PWrapper.build(4, P2PCommand.EXTA_REMOVE_TAGS, rt)).getData();
            Assertions.assertEquals(2L, w2.numericValue, "removeTags cat+dog=2");

            ExtaRemoveAllTagsRequest ra = new ExtaRemoveAllTagsRequest(SA, PA, SB, PB, 0L, entryKey);
            ExtAttrsWriteAck w3 = (ExtAttrsWriteAck) new ExtaRemoveAllTagsServerHandler().process(
                    P2PWrapper.build(5, P2PCommand.EXTA_REMOVE_ALL_TAGS, ra)).getData();
            Assertions.assertEquals(3L, w3.numericValue, "removeAllTags=3");

            ExtaRemoveAllTagsRequest ra2 = new ExtaRemoveAllTagsRequest(SA, PA, SB, PB, 0L, entryKey);
            ExtAttrsWriteAck w4 = (ExtAttrsWriteAck) new ExtaRemoveAllTagsServerHandler().process(
                    P2PWrapper.build(6, P2PCommand.EXTA_REMOVE_ALL_TAGS, ra2)).getData();
            Assertions.assertEquals(0L, w4.numericValue, "空后再removeAllTags=0（幂等）");

            ExtaListTagsRequest ls = new ExtaListTagsRequest(SA, PA, SB, PB, 0L, entryKey);
            ExtAttrsListStringAck lA3 = (ExtAttrsListStringAck) new ExtaListTagsServerHandler().process(
                    P2PWrapper.build(7, P2PCommand.EXTA_LIST_TAGS, ls)).getData();
            Assertions.assertTrue(lA3.values.isEmpty(), "清空后listTags空");
        }
    }

    @Test
    void T03_DESC_Add_List_Last_RemoveLast_RemoveAll_Seq_ok() throws Exception {
        File tmpA = tmpDir("dsreg_tgt5_A3_");
        File tmpB = tmpDir("dsreg_tgt5_B3_");
        try (DsRegistry rA = new DsRegistry(new File(tmpA, SA + "_" + PA).getAbsolutePath(), SA, PA);
             DsRegistry rB = new DsRegistry(new File(tmpB, SB + "_" + PB).getAbsolutePath(), SB, PB)) {
            Tgt3RegistryResolver.injectForTest(SA, PA, rA);
            Tgt3RegistryResolver.injectForTest(SB, PB, rB);
            String entryKey = "/docs/descdoc";

            ExtaAddDescRequest d1 = new ExtaAddDescRequest(SA, PA, SB, PB, 0L, entryKey, "descV1");
            ExtaAddDescRequest d2 = new ExtaAddDescRequest(SA, PA, SB, PB, 0L, entryKey, "descV2");
            ExtaAddDescRequest d3 = new ExtaAddDescRequest(SA, PA, SB, PB, 0L, entryKey, "descV3");
            long s1 = ((ExtAttrsWriteAck) new ExtaAddDescServerHandler().process(P2PWrapper.build(1, P2PCommand.EXTA_ADD_DESC, d1)).getData()).numericValue;
            long s2 = ((ExtAttrsWriteAck) new ExtaAddDescServerHandler().process(P2PWrapper.build(2, P2PCommand.EXTA_ADD_DESC, d2)).getData()).numericValue;
            long s3 = ((ExtAttrsWriteAck) new ExtaAddDescServerHandler().process(P2PWrapper.build(3, P2PCommand.EXTA_ADD_DESC, d3)).getData()).numericValue;
            Assertions.assertEquals(1L, s1, "seq1=1");
            Assertions.assertEquals(2L, s2, "seq2=2");
            Assertions.assertEquals(3L, s3, "seq3=3");

            ExtaListDescsRequest ld = new ExtaListDescsRequest(SA, PA, SB, PB, 0L, entryKey);
            ExtAttrsListStringAck lA = (ExtAttrsListStringAck) new ExtaListDescsServerHandler().process(
                    P2PWrapper.build(4, P2PCommand.EXTA_LIST_DESCS, ld)).getData();
            Assertions.assertEquals(Arrays.asList("descV1", "descV2", "descV3"), lA.values, "DESC顺序严格1-2-3");

            ExtaLastDescRequest ls = new ExtaLastDescRequest(SA, PA, SB, PB, 0L, entryKey);
            ExtAttrsLastStringAck lsa = (ExtAttrsLastStringAck) new ExtaLastDescServerHandler().process(
                    P2PWrapper.build(5, P2PCommand.EXTA_LAST_DESC, ls)).getData();
            Assertions.assertTrue(lsa.present, "last DESC present");
            Assertions.assertEquals("descV3", lsa.stringValue, "last=V3");

            ExtaRemoveLastDescRequest rl = new ExtaRemoveLastDescRequest(SA, PA, SB, PB, 0L, entryKey);
            ExtAttrsWriteAck rlA = (ExtAttrsWriteAck) new ExtaRemoveLastDescServerHandler().process(
                    P2PWrapper.build(6, P2PCommand.EXTA_REMOVE_LAST_DESC, rl)).getData();
            Assertions.assertEquals(1L, rlA.numericValue, "removeLast DESC ok");

            ExtaLastDescRequest ls2 = new ExtaLastDescRequest(SA, PA, SB, PB, 0L, entryKey);
            ExtAttrsLastStringAck lsa2 = (ExtAttrsLastStringAck) new ExtaLastDescServerHandler().process(
                    P2PWrapper.build(7, P2PCommand.EXTA_LAST_DESC, ls2)).getData();
            Assertions.assertEquals("descV2", lsa2.stringValue, "删尾后last=V2");

            ExtaRemoveAllDescsRequest ra = new ExtaRemoveAllDescsRequest(SA, PA, SB, PB, 0L, entryKey);
            ExtAttrsWriteAck raA = (ExtAttrsWriteAck) new ExtaRemoveAllDescsServerHandler().process(
                    P2PWrapper.build(8, P2PCommand.EXTA_REMOVE_ALL_DESCS, ra)).getData();
            Assertions.assertEquals(2L, raA.numericValue, "removeAllDescs=2剩");

            ExtaAddDescRequest d4 = new ExtaAddDescRequest(SA, PA, SB, PB, 0L, entryKey, "descV4");
            long s4 = ((ExtAttrsWriteAck) new ExtaAddDescServerHandler().process(P2PWrapper.build(9, P2PCommand.EXTA_ADD_DESC, d4)).getData()).numericValue;
            Assertions.assertEquals(1L, s4, "removeAll后重新编号seq=1");
        }
    }

    @Test
    void T04_SPEC_MultiVersion5_RemoveLast3_Left2_ok() throws Exception {
        File tmpA = tmpDir("dsreg_tgt5_A4_");
        File tmpB = tmpDir("dsreg_tgt5_B4_");
        try (DsRegistry rA = new DsRegistry(new File(tmpA, SA + "_" + PA).getAbsolutePath(), SA, PA);
             DsRegistry rB = new DsRegistry(new File(tmpB, SB + "_" + PB).getAbsolutePath(), SB, PB)) {
            Tgt3RegistryResolver.injectForTest(SA, PA, rA);
            Tgt3RegistryResolver.injectForTest(SB, PB, rB);
            String entryKey = "/items/specdoc";
            for (int i = 1; i <= 5; i++) {
                ExtaAddSpecRequest rq = new ExtaAddSpecRequest(SA, PA, SB, PB, 0L, entryKey, "specV" + i);
                P2PWrapper.build(i, P2PCommand.EXTA_ADD_SPEC, rq);
                new ExtaAddSpecServerHandler().process(P2PWrapper.build(i, P2PCommand.EXTA_ADD_SPEC, rq));
            }
            ExtaListSpecsRequest ls = new ExtaListSpecsRequest(SA, PA, SB, PB, 0L, entryKey);
            List<String> vals = ((ExtAttrsListStringAck) new ExtaListSpecsServerHandler().process(
                    P2PWrapper.build(6, P2PCommand.EXTA_LIST_SPECS, ls)).getData()).values;
            Assertions.assertEquals(5, vals.size(), "5条 SPEC");

            for (int i = 0; i < 3; i++) {
                ExtaRemoveLastSpecRequest rl = new ExtaRemoveLastSpecRequest(SA, PA, SB, PB, 0L, entryKey);
                ExtAttrsWriteAck w = (ExtAttrsWriteAck) new ExtaRemoveLastSpecServerHandler().process(
                        P2PWrapper.build(7 + i, P2PCommand.EXTA_REMOVE_LAST_SPEC, rl)).getData();
                Assertions.assertEquals(1L, w.numericValue, "removeLast" + i + " ok");
            }
            ExtaListSpecsRequest ls2 = new ExtaListSpecsRequest(SA, PA, SB, PB, 0L, entryKey);
            List<String> vals2 = ((ExtAttrsListStringAck) new ExtaListSpecsServerHandler().process(
                    P2PWrapper.build(10, P2PCommand.EXTA_LIST_SPECS, ls2)).getData()).values;
            Assertions.assertEquals(Arrays.asList("specV1", "specV2"), vals2, "删3剩2=V1,V2");
        }
    }

    @Test
    void T05_COMMENT_NullText_EmptyEntry_ok() throws Exception {
        File tmpA = tmpDir("dsreg_tgt5_A5_");
        File tmpB = tmpDir("dsreg_tgt5_B5_");
        try (DsRegistry rA = new DsRegistry(new File(tmpA, SA + "_" + PA).getAbsolutePath(), SA, PA);
             DsRegistry rB = new DsRegistry(new File(tmpB, SB + "_" + PB).getAbsolutePath(), SB, PB)) {
            Tgt3RegistryResolver.injectForTest(SA, PA, rA);
            Tgt3RegistryResolver.injectForTest(SB, PB, rB);

            ExtaAddCommentRequest c1 = new ExtaAddCommentRequest(SA, PA, SB, PB, 0L, "/items/c1", null);
            ExtAttrsWriteAck w1 = (ExtAttrsWriteAck) new ExtaAddCommentServerHandler().process(
                    P2PWrapper.build(1, P2PCommand.EXTA_ADD_COMMENT, c1)).getData();
            Assertions.assertTrue(w1.ok, "null comment 自动转空串 成功");
            Assertions.assertEquals(1L, w1.numericValue, "seq=1");

            ExtaLastCommentRequest l1 = new ExtaLastCommentRequest(SA, PA, SB, PB, 0L, "/items/c1");
            ExtAttrsLastStringAck ls = (ExtAttrsLastStringAck) new ExtaLastCommentServerHandler().process(
                    P2PWrapper.build(2, P2PCommand.EXTA_LAST_COMMENT, l1)).getData();
            Assertions.assertEquals("", ls.stringValue, "null转空串写入 回读为空");

            ExtaListCommentsRequest lc = new ExtaListCommentsRequest(SA, PA, SB, PB, 0L, "/items/notexist");
            ExtAttrsListStringAck lA = (ExtAttrsListStringAck) new ExtaListCommentsServerHandler().process(
                    P2PWrapper.build(3, P2PCommand.EXTA_LIST_COMMENTS, lc)).getData();
            Assertions.assertTrue(lA.values.isEmpty(), "空entry listComments 空");

            ExtaLastCommentRequest lc2 = new ExtaLastCommentRequest(SA, PA, SB, PB, 0L, "/items/notexist");
            ExtAttrsLastStringAck lsa2 = (ExtAttrsLastStringAck) new ExtaLastCommentServerHandler().process(
                    P2PWrapper.build(4, P2PCommand.EXTA_LAST_COMMENT, lc2)).getData();
            Assertions.assertFalse(lsa2.present, "空entry lastComment present=false");
            Assertions.assertNull(lsa2.stringValue);

            ExtaRemoveLastCommentRequest rl = new ExtaRemoveLastCommentRequest(SA, PA, SB, PB, 0L, "/items/notexist");
            ExtAttrsWriteAck rw = (ExtAttrsWriteAck) new ExtaRemoveLastCommentServerHandler().process(
                    P2PWrapper.build(5, P2PCommand.EXTA_REMOVE_LAST_COMMENT, rl)).getData();
            Assertions.assertEquals(0L, rw.numericValue, "空entry removeLast=0");
        }
    }

    @Test
    void T06_4Type_3Entries_ZeroCrossIsolation_ok() throws Exception {
        File tmpA = tmpDir("dsreg_tgt5_A6_");
        File tmpB = tmpDir("dsreg_tgt5_B6_");
        try (DsRegistry rA = new DsRegistry(new File(tmpA, SA + "_" + PA).getAbsolutePath(), SA, PA);
             DsRegistry rB = new DsRegistry(new File(tmpB, SB + "_" + PB).getAbsolutePath(), SB, PB)) {
            Tgt3RegistryResolver.injectForTest(SA, PA, rA);
            Tgt3RegistryResolver.injectForTest(SB, PB, rB);
            String[] entries = new String[]{"/a/p1", "/b/p2", "/c/p3"};

            for (int i = 0; i < entries.length; i++) {
                String ek = entries[i];
                new ExtaAddTagServerHandler().process(P2PWrapper.build(1 + i * 4, P2PCommand.EXTA_ADD_TAG,
                        new ExtaAddTagRequest(SA, PA, SB, PB, 0L, ek, "tag" + ek.replace('/', '_'))));
                new ExtaAddDescServerHandler().process(P2PWrapper.build(2 + i * 4, P2PCommand.EXTA_ADD_DESC,
                        new ExtaAddDescRequest(SA, PA, SB, PB, 0L, ek, "desc-of-" + ek)));
                new ExtaAddSpecServerHandler().process(P2PWrapper.build(3 + i * 4, P2PCommand.EXTA_ADD_SPEC,
                        new ExtaAddSpecRequest(SA, PA, SB, PB, 0L, ek, "spec-of-" + ek)));
                new ExtaAddCommentServerHandler().process(P2PWrapper.build(4 + i * 4, P2PCommand.EXTA_ADD_COMMENT,
                        new ExtaAddCommentRequest(SA, PA, SB, PB, 0L, ek, "comment-of-" + ek)));
            }

            for (int i = 0; i < entries.length; i++) {
                String ek = entries[i];
                Set<String> expectTags = new HashSet<>();
                expectTags.add("tag" + ek.replace('/', '_'));
                Set<String> realTags = new HashSet<>(((ExtAttrsListStringAck) new ExtaListTagsServerHandler().process(
                        P2PWrapper.build(100 + i, P2PCommand.EXTA_LIST_TAGS, new ExtaListTagsRequest(SA, PA, SB, PB, 0L, ek))).getData()).values);
                Assertions.assertEquals(expectTags, realTags, ek + " tags self 仅自己");
                realTags.removeAll(expectTags);
                Assertions.assertTrue(realTags.isEmpty(), ek + " tags 无其他entry混入");

                List<String> ds = ((ExtAttrsListStringAck) new ExtaListDescsServerHandler().process(
                        P2PWrapper.build(200 + i, P2PCommand.EXTA_LIST_DESCS, new ExtaListDescsRequest(SA, PA, SB, PB, 0L, ek))).getData()).values;
                Assertions.assertEquals(1, ds.size(), ek + " DESCs size=1");
                Assertions.assertEquals("desc-of-" + ek, ds.get(0));

                List<String> sp = ((ExtAttrsListStringAck) new ExtaListSpecsServerHandler().process(
                        P2PWrapper.build(300 + i, P2PCommand.EXTA_LIST_SPECS, new ExtaListSpecsRequest(SA, PA, SB, PB, 0L, ek))).getData()).values;
                Assertions.assertEquals(1, sp.size());
                Assertions.assertEquals("spec-of-" + ek, sp.get(0));

                List<String> cm = ((ExtAttrsListStringAck) new ExtaListCommentsServerHandler().process(
                        P2PWrapper.build(400 + i, P2PCommand.EXTA_LIST_COMMENTS, new ExtaListCommentsRequest(SA, PA, SB, PB, 0L, ek))).getData()).values;
                Assertions.assertEquals(1, cm.size());
                Assertions.assertEquals("comment-of-" + ek, cm.get(0));
            }
        }
    }

    @Test
    void T07_DualRoute_Fallback_PlusWrongBoth_STDError() throws Exception {
        File tmpA = tmpDir("dsreg_tgt5_A7_");
        File tmpB = tmpDir("dsreg_tgt5_B7_");
        try (DsRegistry rA = new DsRegistry(new File(tmpA, SA + "_" + PA).getAbsolutePath(), SA, PA);
             DsRegistry rB = new DsRegistry(new File(tmpB, SB + "_" + PB).getAbsolutePath(), SB, PB)) {
            Tgt3RegistryResolver.injectForTest(SA, PA, rA);
            Tgt3RegistryResolver.injectForTest(SB, PB, rB);
            String entryKey = "/apps/g7";

            new ExtaAddTagServerHandler().process(P2PWrapper.build(1, P2PCommand.EXTA_ADD_TAG,
                    new ExtaAddTagRequest(SA, PA, SB, PB, 0L, entryKey, "g7tag")));

            long dirNodeId = rB.mkdirs("/tmp/g");
            long g = rB.toGlobalNodeId(dirNodeId);
            ExtaListTagsRequest fk2 = new ExtaListTagsRequest(SA, PA, -1, -1, g, entryKey);
            P2PWrapper wk2 = new ExtaListTagsServerHandler().process(P2PWrapper.build(2, P2PCommand.EXTA_LIST_TAGS, fk2));
            List<String> klist2 = ((ExtAttrsListStringAck) wk2.getData()).values;
            Assertions.assertEquals(new LinkedHashSet<>(Arrays.asList("g7tag")), new LinkedHashSet<>(klist2),
                    "globalNodeId fallback 路由到 SB/PB 拿到 tag=g7tag");

            ExtaListTagsRequest fk3 = new ExtaListTagsRequest(SA, PA, -1, -1, 0L, entryKey);
            P2PCommand cmd = new ExtaListTagsServerHandler().process(P2PWrapper.build(3, P2PCommand.EXTA_LIST_TAGS, fk3)).getCommand();
            Assertions.assertEquals(P2PCommand.STD_ERROR, cmd, "双模式失败→STD_ERROR");

            ExtaListTagsFilteredRequest fk4 = new ExtaListTagsFilteredRequest(SA, PA, -1, -1, 0L, entryKey, 0, null);
            P2PCommand cmd2 = new ExtaListTagsFilteredServerHandler().process(P2PWrapper.build(4, P2PCommand.EXTA_LIST_TAGS_FILTERED, fk4)).getCommand();
            Assertions.assertEquals(P2PCommand.STD_ERROR, cmd2, "Filtered 双模式失败→STD_ERROR");

            ExtaAddDescRequest wr = new ExtaAddDescRequest(SA, PA, -1, -1, 0L, entryKey, "xxx");
            P2PCommand cmd3 = new ExtaAddDescServerHandler().process(P2PWrapper.build(5, P2PCommand.EXTA_ADD_DESC, wr)).getCommand();
            Assertions.assertEquals(P2PCommand.STD_ERROR, cmd3, "AddDesc 双模式失败→STD_ERROR");
        }
    }
}
