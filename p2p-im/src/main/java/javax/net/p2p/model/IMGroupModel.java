package javax.net.p2p.model;

import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.AllArgsConstructor;
import lombok.Builder;
import java.util.List;

/**
 * IM群组模型
 * 用于群组创建、解散、信息查询等
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class IMGroupModel {
    /** 群组ID */
    private String groupId;
    
    /** 群组名称 */
    private String name;
    
    /** 群组公告 */
    private String announcement;
    
    /** 群主ID */
    private String ownerId;
    
    /** 管理员列表 */
    private List<String> admins;
    
    /** 成员列表 */
    private List<String> members;
    
    /** 群组类型 (PUBLIC, PRIVATE, TEMP) */
    private String type;
    
    /** 创建时间 */
    private long createTime;
    
    /** 最大成员数 */
    private int maxMembers;
    
    /** 当前成员数 */
    private int memberCount;
    
    /** 群头像 */
    private String avatar;
    
    /** 群组状态 (ACTIVE, DISMISSED, BLOCKED) */
    private String status;
}
