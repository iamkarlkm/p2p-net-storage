
package com.q3lives.chdfs;

import com.qcloud.cos.endpoint.EndpointBuilder;
import com.qcloud.cos.region.Region;

/**
 * COS EndpointBuilder 的自定义实现：按 region + domain 拼接生成 Endpoint。
 */
public class SelfDefinedEndpointBuilder implements EndpointBuilder {
	private String region;

	private String domain;

	public SelfDefinedEndpointBuilder(String region, String domain) {
		super();
		// 格式化 Region
		this.region = Region.formatRegion(new Region(region));
		this.domain = domain;
	}

	@Override
	public String buildGeneralApiEndpoint(String bucketName) {
		// 构造 Endpoint
		String endpoint = String.format("%s.%s", this.region, this.domain);
		// 构造 Bucket 访问域名
		return String.format("%s.%s", bucketName, endpoint);
	}

	@Override
	public String buildGetServiceApiEndpoint() {
		return String.format("%s.%s", this.region, this.domain);
	}
}
