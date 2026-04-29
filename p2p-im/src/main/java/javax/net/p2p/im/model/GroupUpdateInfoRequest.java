package javax.net.p2p.im.model;

import java.io.Serializable;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class GroupUpdateInfoRequest implements Serializable {
    private String groupId;
    private String operatorId;
    private String name;
    private String announcement;
    private String avatar;
}

