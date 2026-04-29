package javax.net.p2p.im.runtime;

import javax.net.p2p.model.IMGroupModel;
import org.junit.Assert;
import org.junit.Test;

public class ImRuntimeGroupListFilterTest {
    @Test
    public void testListGroupsForUserFiltersByMembership() {
        ImRuntime.clearAll();

        IMGroupModel g = IMGroupModel.builder()
            .name("g1")
            .ownerId("user_001")
            .build();
        IMGroupModel created = ImRuntime.createGroup(g);
        String groupId = created.getGroupId();

        Assert.assertTrue(ImRuntime.listGroupsForUser("user_001").stream().anyMatch(x -> groupId.equals(x.getGroupId())));
        Assert.assertFalse(ImRuntime.listGroupsForUser("user_002").stream().anyMatch(x -> groupId.equals(x.getGroupId())));

        ImRuntime.joinGroup("user_002", groupId);
        Assert.assertTrue(ImRuntime.listGroupsForUser("user_002").stream().anyMatch(x -> groupId.equals(x.getGroupId())));
        Assert.assertFalse(ImRuntime.listGroupsForUser("user_003").stream().anyMatch(x -> groupId.equals(x.getGroupId())));

        ImRuntime.removeMember("user_001", groupId, "user_002");
        Assert.assertFalse(ImRuntime.listGroupsForUser("user_002").stream().anyMatch(x -> groupId.equals(x.getGroupId())));

        ImRuntime.dismissGroup("user_001", groupId);
        Assert.assertFalse(ImRuntime.listGroupsForUser("user_001").stream().anyMatch(x -> groupId.equals(x.getGroupId())));
    }
}

