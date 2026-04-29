
package com.q3lives.chdfs;

import com.qcloud.cos.COSClient;
import com.qcloud.cos.ClientConfig;
import com.qcloud.cos.auth.BasicCOSCredentials;
import com.qcloud.cos.auth.COSCredentials;
import com.qcloud.cos.exception.CosClientException;
import com.qcloud.cos.exception.CosServiceException;
import com.qcloud.cos.http.HttpProtocol;
import com.qcloud.cos.model.Bucket;
import com.qcloud.cos.model.COSObject;
import com.qcloud.cos.model.COSObjectInputStream;
import com.qcloud.cos.model.COSObjectSummary;
import com.qcloud.cos.model.CannedAccessControlList;
import com.qcloud.cos.model.CompleteMultipartUploadRequest;
import com.qcloud.cos.model.CompleteMultipartUploadResult;
import com.qcloud.cos.model.CopyObjectRequest;
import com.qcloud.cos.model.CopyObjectResult;
import com.qcloud.cos.model.CreateBucketRequest;
import com.qcloud.cos.model.GetObjectRequest;
import com.qcloud.cos.model.InitiateMultipartUploadRequest;
import com.qcloud.cos.model.InitiateMultipartUploadResult;
import com.qcloud.cos.model.ListMultipartUploadsRequest;
import com.qcloud.cos.model.ListObjectsRequest;
import com.qcloud.cos.model.ListPartsRequest;
import com.qcloud.cos.model.MultipartUpload;
import com.qcloud.cos.model.MultipartUploadListing;
import com.qcloud.cos.model.ObjectListing;
import com.qcloud.cos.model.ObjectMetadata;
import com.qcloud.cos.model.PartETag;
import com.qcloud.cos.model.PartListing;
import com.qcloud.cos.model.PartSummary;
import com.qcloud.cos.model.PutObjectRequest;
import com.qcloud.cos.model.PutObjectResult;
import com.qcloud.cos.model.RenameRequest;
import com.qcloud.cos.model.UploadPartRequest;
import com.qcloud.cos.model.UploadPartResult;
import com.qcloud.cos.region.Region;
import java.io.ByteArrayInputStream;
import java.io.File;
import java.util.ArrayList;
import java.util.LinkedList;
import java.util.List;
import java.util.Timer;
import java.util.TimerTask;
import java.util.concurrent.ArrayBlockingQueue;
import javax.net.p2p.utils.SecurityUtils;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.io.IOUtils;

/**
 * 展示了创建bucket, 删除bucket, 查询bucket是否存在的demo
 *
 */
/**
 *
 * @author karl
 */
@Slf4j
public class CosUtil {

	//private final static String BUCKET_NAME = "yzkj-dzyxdagl-1255000016";

	private final static String BUCKET_NAMES[] = { "yzkj-dzyxdagl-01-1255000016", "yzkj-dzyxdagl-02-1255000016",
			"yzkj-dzyxdagl-03-1255000016", "yzkj-dzyxdagl-04-1255000016",
			"yzkj-dzyxdagl-05-1255000016", "yzkj-dzyxdagl-06-1255000016", "yzkj-dzyxdagl-07-1255000016",
			"yzkj-dzyxdagl-08-1255000016", "yzkj-dzyxdagl-09-1255000016",
			"yzkj-dzyxdagl-10-1255000016", "yzkj-dzyxdagl-11-1255000016", "yzkj-dzyxdagl-12-1255000016",
			"yzkj-dzyxdagl-13-1255000016", "yzkj-dzyxdagl-14-1255000016",
			"yzkj-dzyxdagl-15-1255000016", "yzkj-dzyxdagl-16-1255000016" };

	private final static String SECRET_ID = "SECRET_ID";

	private final static String SECRET_KEY = "SECRET_KEY";

	//private final static String COS_REGION = "kmbdc";

	private final static String COS_REGION_STR = "cos.kmbdc";

	private final static Region COS_REGION = new Region(COS_REGION_STR);

	private final static String COS_DOMAIN = "csp.bigdata.kms.yn";

	private final static String COS_USER = "yzkj";

	private final static String COS_PWD = "Tcdn@20072007";

	//	private static COSClient COS_CLIENT;

	private static final long TOCKEN_TIMEOUT = 6 * 3600 * 1000l;

	private static final int CLIENT_QUEUE_SIZE = 32;

    /**
     * 池化client,以防止经常发生timeout错误
     */
	private static final ArrayBlockingQueue<COSClient> CLIENT_QUEUE = new ArrayBlockingQueue<>(CLIENT_QUEUE_SIZE);

	//	private static Timer COS_CLIENT_TIMEOUT_TIMER;

	static {
		initClientQueue();
	}

	public static void main1(String[] args) throws Exception {
		//System.out.println(new SelfDefinedEndpointBuilder(COS_REGION_STR,COS_DOMAIN).buildGeneralApiEndpoint(BUCKET_NAME));
	}

	public static COSClient getInstance() throws InterruptedException {
			//		if (COS_CLIENT == null) {
			//			synchronized (CosUtil.class) {
			//				if (COS_CLIENT == null) {
			//
			//					init();
			//
			//				}
			//			}
			//		}
			//		return COS_CLIENT;
			return CLIENT_QUEUE.take();
	}
	
	public static void releaseClient( COSClient  client){
		if(client!=null){
			CLIENT_QUEUE.offer(client);
		}
	}

	private static void initClientQueue() {
		if (CLIENT_QUEUE.isEmpty()) {
			for (int i = 0; i < CLIENT_QUEUE_SIZE; i++) {
				CLIENT_QUEUE.offer(createClient());
			}
		} else {
			int size = CLIENT_QUEUE.size();
			for (int i = 0; i < size; i++) {
				COSClient client;
				try {
					client = CLIENT_QUEUE.take();
					client.shutdown();
					CLIENT_QUEUE.offer(createClient());
				} catch (InterruptedException ex) {
					log.error("在tocken过期之前重新生成客户端exception:", ex.getMessage());
				}
			}
		}

		Timer timer = new Timer();
		timer.schedule(new TimerTask() {
			public void run() {
				log.info("在tocken过期之前重新生成客户端");
				initClientQueue();
			}
		}, TOCKEN_TIMEOUT - 300 * 1000l);// 在tocken过期之前重新生成客户端
	}

	private static COSClient createClient() {

		// 1 初始化用户身份信息(appid, secretId, secretKey)
		COSCredentials cred = new BasicCOSCredentials(SECRET_ID, SECRET_KEY);
		// 2 设置bucket的区域, COS地域的简称请参照 https://www.qcloud.com/document/product/436/6224
		ClientConfig clientConfig = new ClientConfig(COS_REGION);
		//设置自定义COS服务器
		//SelfDefinedEndpointBuilder d;
		clientConfig.setEndpointBuilder(new SelfDefinedEndpointBuilder(COS_REGION_STR, COS_DOMAIN));
		clientConfig.setHttpProtocol(HttpProtocol.http);
		clientConfig.setSignExpired(TOCKEN_TIMEOUT);
		clientConfig.setConnectionTimeout(30*1000);
		clientConfig.setRequestTimeout(300*1000);
		clientConfig.setRequestTimeOutEnable(true);
		// 3 生成cos客户端
		System.out.println("生成cos客户端:" + clientConfig.getEndpointBuilder().buildGetServiceApiEndpoint());
		COSClient COS_CLIENT = new COSClient(cred, clientConfig);
		//					System.out.println("current buckets:" + COS_CLIENT.listBuckets());
		//					for (Bucket bucket : COS_CLIENT.listBuckets()) {
		//						System.out.println(bucket);
		//					}
		//COS_CLIENT.setBucketDomainConfiguration(BUCKET_NAME, configuration);
		log.info("客户端{}创建成功", COS_CLIENT);
		return COS_CLIENT;
	}

	public static void shutdown() {
		for (int i = 0; i < CLIENT_QUEUE_SIZE; i++) {
			COSClient client;
			try {
				client = CLIENT_QUEUE.take();
				client.shutdown();
			} catch (InterruptedException ex) {
				log.error(ex.getMessage());
			}
		}
	}

	public static byte[] read(String path) throws Exception {
	COSClient cosClient = null;
		byte[] data = null;
		try {
			log.info(" read getClient from queue:{}", CLIENT_QUEUE.size());
			cosClient = getInstance();
			log.info("read {}",  path);
			ObjectMetadata objectMetadata = cosClient.getObjectMetadata(getBucketName(path), path);

			data = new byte[(int) objectMetadata.getContentLength()];
			GetObjectRequest getObjectRequest = new GetObjectRequest(getBucketName(path), path);

			try (COSObject cosObject = cosClient.getObject(getObjectRequest);
					COSObjectInputStream in = cosObject.getObjectContent()) {
				IOUtils.readFully(in, data, 0, data.length);
			}
			log.info("read success {}",  path);
			return data;
		} catch (Exception e) {
			log.error(e.getMessage());
			ObjectMetadata objectMetadata = cosClient.getObjectMetadata(getBucketName(path), path);
			data = new byte[(int) objectMetadata.getContentLength()];
			GetObjectRequest getObjectRequest = new GetObjectRequest(getBucketName(path), path);

			try (COSObject cosObject = cosClient.getObject(getObjectRequest);
					COSObjectInputStream in = cosObject.getObjectContent()) {
				IOUtils.readFully(in, data, 0, data.length);
				log.info("read success {}",  path);
				return data;
			}catch (Exception ex) {
				log.info("read part fialed {} -> {}", path, ex.getMessage());
				throw new RuntimeException(ex.getMessage());
			} 
		}finally{
			log.info("{} read releaseClient {}",  path,cosClient.toString());
			releaseClient(cosClient);
		}

	}

	public static byte[] readPart(String path, long start, int count) throws Exception {
		COSClient cosClient = null;
		byte[] data = null;
		try {
			log.info("read part {},start={},count={}", path, start, count);
			cosClient = getInstance();
			data = new byte[count];
			GetObjectRequest getObjectRequest = new GetObjectRequest(getBucketName(path), path);
			getObjectRequest.setRange(start, start + count - 1);
			try (COSObject cosObject = cosClient.getObject(getObjectRequest);
					COSObjectInputStream in = cosObject.getObjectContent()) {
				IOUtils.readFully(in, data, 0, data.length);
				//String md5 =cosObject.getObjectMetadata().getETag();
			}
			log.info("read part success {}", path);
			return data;
		} catch (Exception e) {
			log.error(e.getMessage());
			data = new byte[count];
			GetObjectRequest getObjectRequest = new GetObjectRequest(getBucketName(path), path);
			getObjectRequest.setRange(start, start + count - 1);
			try (COSObject cosObject = cosClient.getObject(getObjectRequest);
					COSObjectInputStream in = cosObject.getObjectContent()) {
				IOUtils.readFully(in, data, 0, data.length);
				return data;
			} catch (Exception ex) {
				log.info("read part fialed {} -> {}", path, ex.getMessage());
				throw new RuntimeException(ex.getMessage());
			} 
		}finally {
				releaseClient(cosClient);
			}

	}

	public static void copyFromLocalFile(String path, String localFilePath) throws Exception {
		COSClient cosClient = getInstance();
		PutObjectResult result = cosClient.putObject(getBucketName(path), path, new File(localFilePath));

		System.out.println("Metadata:" + result.getMetadata());
		releaseClient(cosClient);
	}

	public static void copyToLocalFile(String path, String localFilePath) throws Exception {
		COSClient cosClient = getInstance();
		GetObjectRequest getObjectRequest = new GetObjectRequest(getBucketName(path), path);
		ObjectMetadata objectMetadata = cosClient.getObject(getObjectRequest, new File(localFilePath));
			releaseClient(cosClient);
		System.out.println("Metadata:" + objectMetadata);
		
	}

	public static void cp(String srcKey, String destKey) throws Exception {
		COSClient cosClient = getInstance();
		CopyObjectRequest copyObjectRequest = new CopyObjectRequest(COS_REGION, getBucketName(srcKey),
				srcKey, getBucketName(destKey), destKey);
		try {
			CopyObjectResult copyObjectResult = cosClient.copyObject(copyObjectRequest);
			//System.out.println("copyObjectResult:" + copyObjectResult);
		} catch (CosServiceException e) {
			e.printStackTrace();
		} catch (CosClientException e) {
			e.printStackTrace();
		}finally{
			releaseClient(cosClient);
		}
	}

	public static void mv(String srcKey, String destKey) throws Exception {
		COSClient cosClient = getInstance();
		//CopyObjectRequest copyObjectRequest = new CopyObjectRequest(COS_REGION, );
		try {
			//cosClient.listObjects(srcKey)
			cosClient.copyObject(getBucketName(srcKey),
					srcKey, getBucketName(destKey), destKey);
			//System.out.println("copyObjectResult:" + copyObjectResult);
			if (exists(destKey)) {
				cosClient.deleteObject(getBucketName(srcKey), srcKey);
			}
		} catch (CosServiceException e) {
			e.printStackTrace();
		} catch (CosClientException e) {
			e.printStackTrace();
		}finally{
			releaseClient(cosClient);
		}
	}

	public static boolean exists(String path) throws Exception {
		COSClient cosClient = null;
		try {
			log.info("exists getClient from queue:{}", CLIENT_QUEUE.size());
			cosClient = getInstance();
			log.info("exists {}", path);
			ObjectMetadata objectMetadata = cosClient.getObjectMetadata(getBucketName(path), path);
		return objectMetadata != null && objectMetadata.getContentLength() > 0;
		}catch(Exception e){
			log.error("exists failed:{} -> {}", path,e.getMessage());
		}finally{
			log.info("exists success releaseClient {} -> {}",  path,cosClient);
			releaseClient(cosClient);
		}
		return false;
	}

	public static ObjectMetadata getObjectInfo(String path) throws Exception {
		COSClient cosClient = null;
		try {
			log.info("getObjectInfo getClient from queue:{}", CLIENT_QUEUE.size());
			cosClient = getInstance();
			log.info("getObjectInfo {}",  path);
			ObjectMetadata objectMetadata = cosClient.getObjectMetadata(getBucketName(path), path);
			return objectMetadata;
		}catch(Exception e){
			log.error("{} getObjectInfo failed {} -> {} ", path,e.getMessage());
		}finally{
			log.info("getObjectInfo success releaseClient {} -> {}",  path,cosClient);
			releaseClient(cosClient);
		}
		return null;
	}

	// 删除单个文件(不带版本号, 即bucket未开启多版本)
	public static boolean remove(String path) {
		COSClient cosClient = null;
		try {
			cosClient = getInstance();
			cosClient.deleteObject(getBucketName(path), path);
			releaseClient(cosClient);
			return true;
		} catch (CosServiceException e) { // 如果是其他错误, 比如参数错误， 身份验证不过等会抛出CosServiceException
			e.printStackTrace();
		} catch (CosClientException e) { // 如果是客户端错误，比如连接不上COS
			e.printStackTrace();
		} catch (Exception e) { // 该测试用例的预期结果
			e.printStackTrace();
		}finally{
			log.info("{} remove success releaseClient {}",  path,cosClient.toString());
			releaseClient(cosClient);
		}
		return false;

	}

	/**
	 * 分布式算法--根据路径的哈希值取4位对应16个桶
	 * 
	 * @param path
	 * @return
	 */
	public static final String getBucketName(String path) {
		//System.out.println(path +" -> "+(path.hashCode() & 0xf));
		return BUCKET_NAMES[(path.hashCode() & 0xf)];

	}

	public static boolean write(String path, byte[] data) throws Exception {
		COSClient cosClient = null;

		try (
				ByteArrayInputStream in = new ByteArrayInputStream(data)) {
			log.info(" write getClient from queue:{}", CLIENT_QUEUE.size());
			cosClient = getInstance();
			log.info("write data: {} ", path);
			ObjectMetadata objectMetadata = new ObjectMetadata();
			objectMetadata.setHeader("md5", SecurityUtils.toMD5(data));
			//			objectMetadata.setContentMD5(SecurityUtils.toMD5(data));
//			objectMetadata.setETag(objectMetadata.getContentMD5());
			objectMetadata.setContentLength(data.length);
			String bucketName = getBucketName(path);
			PutObjectRequest putObjectRequest = new PutObjectRequest(bucketName, path, in, objectMetadata);
			
			cosClient.putObject(putObjectRequest);

			log.info("write ok,check length: {} ", path);
			objectMetadata = cosClient.getObjectMetadata(bucketName, path);
			return objectMetadata.getContentLength() > 0;
		}finally{
			log.info("{} write success releaseClient {}",  path,cosClient);
			releaseClient(cosClient);
		}
	}

	public static void writePart(String path, int blockIndex, byte[] blockdata, String blockMd5) throws Exception {

		uploadMultipart(path, getMultipartUploadId(path), blockIndex, blockdata, blockMd5);

	}

	public static ObjectMetadata completePart(String path,String md5,long length) throws Exception {
		String uploadId = getMultipartUploadId(path);
		return completeMultipart(path,length, md5,uploadId, listMultiparts(path, uploadId));

	}

	public static String getMultipartUploadId(String path) {
		COSClient cosClient = null;
		try {
			cosClient = getInstance();
			String bucketName = getBucketName(path);
			ListMultipartUploadsRequest listMultipartUploadsRequest = new ListMultipartUploadsRequest(bucketName);
			// 每次请求最多列出多少个
			listMultipartUploadsRequest.setMaxUploads(10);
			// 设置要查询的分块上传任务的目标前缀，直接设置要查询的 key
			listMultipartUploadsRequest.setPrefix(path);
			
			MultipartUploadListing multipartUploadListing = null;
			
			MultipartUpload upaload = null;
			do {
				multipartUploadListing = cosClient.listMultipartUploads(listMultipartUploadsRequest);
				List<MultipartUpload> multipartUploads = multipartUploadListing.getMultipartUploads();
				
				for (MultipartUpload mUpload : multipartUploads) {
					if (mUpload.getKey().equals(path)) {
						System.out.println(mUpload.getUploadId());
						upaload = mUpload;
						break;
					}
				}
				
				if (upaload != null) {
					break;
				}
				listMultipartUploadsRequest.setKeyMarker(multipartUploadListing.getNextKeyMarker());
				listMultipartUploadsRequest.setUploadIdMarker(multipartUploadListing.getNextUploadIdMarker());
			} while (multipartUploadListing.isTruncated());
			
			if (upaload != null) {
				return upaload.getUploadId();
			}
			
			InitiateMultipartUploadRequest request = new InitiateMultipartUploadRequest(bucketName, path);
			
			// 分块上传的过程中，仅能通过初始化分块指定文件上传之后的 metadata
			// 需要的头部可以在这里指定
			ObjectMetadata objectMetadata = new ObjectMetadata();
			request.setObjectMetadata(objectMetadata);
			
			try {
				InitiateMultipartUploadResult initResult = cosClient.initiateMultipartUpload(request);
				// 获取 uploadid
				String uploadId = initResult.getUploadId();
				//    System.out.println(uploadId);
				return uploadId;
			} catch (CosServiceException e) {
				throw e;
			} catch (CosClientException e) {
				throw e;
			}
		} catch (InterruptedException ex) {
			throw new RuntimeException(ex);
		}finally{
			releaseClient(cosClient);
		}
	}

	public static List<PartETag> listMultiparts(String path, String uploadId) {
		COSClient cosClient = null;
		try {
			cosClient = getInstance();
			// 用于保存已上传的分片信息
			List<PartETag> partETags = new LinkedList<>();
			
			PartListing partListing = null;
			ListPartsRequest listPartsRequest = new ListPartsRequest(getBucketName(path), path, uploadId);
			do {
				try {
					partListing = cosClient.listParts(listPartsRequest);
				} catch (CosServiceException e) {
					throw e;
				} catch (CosClientException e) {
					throw e;
				}
				
				for (PartSummary partSummary : partListing.getParts()) {
					partETags.add(new PartETag(partSummary.getPartNumber(), partSummary.getETag()));
					//System.out.println("list multipart upload parts, partNum:" + partSummary.getPartNumber() + ", etag:" + partSummary.getETag());
				}
				
				listPartsRequest.setPartNumberMarker(partListing.getNextPartNumberMarker());
			} while (partListing.isTruncated());
			return partETags;
		} catch (InterruptedException ex) {
			throw new RuntimeException(ex);
		}finally{
			releaseClient(cosClient);
		}

	}

	public static PartETag uploadMultipart(String path, String uploadId, int blockIndex, byte[] blockdata, String blockMd5) {
		COSClient cosClient = null;
		try {
			cosClient = getInstance();
			String bucketName = getBucketName(path);
			
			UploadPartRequest uploadPartRequest = new UploadPartRequest();
			uploadPartRequest.setBucketName(bucketName);
			uploadPartRequest.setKey(path);
			uploadPartRequest.setUploadId(uploadId);
			uploadPartRequest.setInputStream(new ByteArrayInputStream(blockdata));
			// 设置分块的长度
			uploadPartRequest.setPartSize(blockdata.length);
			// 设置要上传的分块编号，从 1 开始
			uploadPartRequest.setPartNumber(blockIndex + 1);
			uploadPartRequest.setMd5Digest(blockMd5);
			
			try {
				UploadPartResult uploadPartResult = cosClient.uploadPart(uploadPartRequest);
				PartETag partETag = uploadPartResult.getPartETag();
				return partETag;
			} catch (CosServiceException e) {
				throw e;
			} catch (CosClientException e) {
				throw e;
			}
			
		} catch (InterruptedException ex) {
			throw new RuntimeException(ex);
		}finally{
			releaseClient(cosClient);
		}

	}

	public static ObjectMetadata completeMultipart(String path,long length,String md5, String uploadId, List<PartETag> partETags) throws Exception {
		COSClient cosClient = null;
		try {
			cosClient = getInstance();
		String bucketName = getBucketName(path);
		// 保存已上传的分片信息，实际情况里，这里的内容是从上传分块接口中获取到的
		//List<PartETag> partETags = new LinkedList<>();

		// 分片上传结束后，调用 complete 完成分片上传
		CompleteMultipartUploadRequest completeMultipartUploadRequest = new CompleteMultipartUploadRequest(bucketName, path, uploadId, partETags);
			ObjectMetadata objectMetadata = completeMultipartUploadRequest.getObjectMetadata();
			objectMetadata.setContentMD5(md5);
			objectMetadata.setETag(objectMetadata.getContentMD5());
			if(objectMetadata.getContentLength()==0
					||objectMetadata.getContentLength()!=length){
				objectMetadata.setContentLength(length);
			}
			completeMultipartUploadRequest.setObjectMetadata(objectMetadata);
			CompleteMultipartUploadResult completeResult = cosClient.completeMultipartUpload(completeMultipartUploadRequest);
			//System.out.println(completeResult.getRequestId());
			completeResult.getCrc64Ecma();
			return getObjectInfo(path);
		} catch (CosServiceException e) {
			throw e;
		} catch (CosClientException e) {
			throw e;
		}finally{
			releaseClient(cosClient);
		}
	}

	/**
	 * 
	 * 
	 * @param path
	 * @return
	 */

	public static List<String> listObjects(String path) {
		COSClient cosClient = null;
		try {
			cosClient = getInstance();
			
			ListObjectsRequest listObjectsRequest = new ListObjectsRequest();
			// 设置bucket名称
			listObjectsRequest.setBucketName(getBucketName(path));
			// prefix表示列出的object的key以prefix开始
			listObjectsRequest.setPrefix(path);
			// 设置最大遍历出多少个对象, 一次listobject最大支持1000
			listObjectsRequest.setMaxKeys(1000);
			// listObjectsRequest.setDelimiter("/");
			ObjectListing objectListing = null;
			try {
				objectListing = cosClient.listObjects(listObjectsRequest);
			} catch (CosServiceException e) {
				e.printStackTrace();
			} catch (CosClientException e) {
				e.printStackTrace();
			}
			// common prefix表示被delimiter截断的路径, 如delimter设置为/, common prefix则表示所有子目录的路径
			//List<String> commonPrefixs = objectListing.getCommonPrefixes();
			List<String> paths = new ArrayList();
			// object summary表示所有列出的object列表
			List<COSObjectSummary> cosObjectSummaries = objectListing.getObjectSummaries();
			for (COSObjectSummary cosObjectSummary : cosObjectSummaries) {
				// 文件的路径key
				//String key = cosObjectSummary.getKey();
				paths.add(cosObjectSummary.getKey());
				//            // 文件的etag
				//            String etag = cosObjectSummary.getETag();
				//            // 文件的长度
				//            long fileSize = cosObjectSummary.getSize();
				//            // 文件的存储类型
				//            String storageClasses = cosObjectSummary.getStorageClass();
				//
				//            System.out.println("key: " + key);
			}
			
			return paths;
		} catch (InterruptedException ex) {
			throw new RuntimeException(ex);
		}finally{
			releaseClient(cosClient);
		}
	}

	public static void main(String[] args) throws Exception {
		//System.out.println(args.length);
		if (args.length == 2 && "cp".equals(args[0])) {
			cp(args[1], args[2]);
		} else if (args.length == 2 && "mv".equals(args[0])) {
			mv(args[1], args[2]);
		} else if (args.length == 2 && "ls".equals(args[0])) {
			List<String> list = listObjects(args[1]);
			System.out.println(args[0] + " " + args[1] + " ->\n" + list);
		} else if (args.length == 2 && "exists".equals(args[0])) {
			System.out.println("exists " + args[0] + " -> " + exists(args[1]));
		} else if (args.length == 2 && "rm".equals(args[0])) {
			System.out.println("rm " + args[0] + " -> " + remove(args[1]));
		} else if (args.length == 3 && "get".equals(args[0])) {
			copyToLocalFile(args[1], args[2]);
			System.out.println(args[0] + " " + args[1] + " " + args[2]);
		} else if (args.length == 3 && "put".equals(args[0])) {
			copyFromLocalFile(args[1], args[2]);
			System.out.println(args[0] + " " + args[1] + " " + args[2]);
		} else if ("create".equals(args[0])) {
			createBucket(args[1]);
			System.out.println(args[0] + " " + args[1]);
		}
		//		System.out.println(HdfsUtil.class.getClassLoader().getResource("core-site.xml"));
	}

	public static void createBucket(String bucketName) {
		COSClient cosClient = null;
		try {
			cosClient = getInstance();
			//存储桶名称，格式：BucketName-APPID
			String bucket = bucketName + "-1255000016";
			CreateBucketRequest createBucketRequest = new CreateBucketRequest(bucket);
			// 设置 bucket 的权限为 PublicRead(公有读私有写), 其他可选有私有读写,
			createBucketRequest.setCannedAcl(CannedAccessControlList.Default);
			try {
				// 通过上一步骤中生成的 CSP 客户端发出 createBucket 请求
				Bucket bucketResult = cosClient.createBucket(createBucketRequest);
				System.out.println(bucketResult);
			} catch (CosServiceException serverException) {
				serverException.printStackTrace();
			} catch (CosClientException clientException) {
				clientException.printStackTrace();
			}
		}catch (InterruptedException ex) {
			throw new RuntimeException(ex);
		}finally{
			releaseClient(cosClient);
		}
	}

	public static void moveAllObjects() {
		COSClient cosClient = null;
		try {
			cosClient = getInstance();
			// bucket名需包含appid
			String bucketName = "yzkj-dzyxdagl-1255000016";
			ListObjectsRequest listObjectsRequest = new ListObjectsRequest();
			// 设置bucket名称
			listObjectsRequest.setBucketName(bucketName);
			// prefix表示列出的object的key以prefix开始
			listObjectsRequest.setPrefix("/gfs/");
			// deliter表示分隔符, 设置为/表示列出当前目录下的object, 设置为空表示列出所有的object
			listObjectsRequest.setDelimiter("");
			// 设置最大遍历出多少个对象, 一次listobject最大支持1000
			listObjectsRequest.setMaxKeys(5000);
			ObjectListing objectListing = null;
			int batchCount = 1;
			do {
				log.info("batch mv start ->" + batchCount);
				try {
					objectListing = cosClient.listObjects(listObjectsRequest);
				} catch (CosServiceException e) {
					e.printStackTrace();
					return;
				} catch (CosClientException e) {
					e.printStackTrace();
					return;
				}
				// common prefix表示表示被delimiter截断的路径, 如delimter设置为/, common prefix则表示所有子目录的路径
				List<String> commonPrefixs = objectListing.getCommonPrefixes();
				// object summary表示所有列出的object列表
				List<COSObjectSummary> cosObjectSummaries = objectListing.getObjectSummaries();
				for (COSObjectSummary cosObjectSummary : cosObjectSummaries) {
					// 文件的路径key
					String key = cosObjectSummary.getKey();
					String keyDest = key;
					if (!key.startsWith("/")) {
						keyDest = "/" + key;
					}
					if (key.contains("gfs/wjid") && key.length() > 62) {//处理旧的非规范命名路径
						keyDest = regenerateDfsPathByWjidShort(keyDest);
					}
					
					System.out.println(bucketName + " -> " + getBucketName(keyDest));
					System.out.println(keyDest);
					cosClient.copyObject(bucketName,
							key, getBucketName(keyDest), keyDest);
					//System.out.println("copyObjectResult:" + copyObjectResult);
					ObjectMetadata objectMetadata = cosClient.getObjectMetadata(getBucketName(keyDest), keyDest);
					
					if (objectMetadata != null && objectMetadata.getContentLength() > 0) {
						cosClient.deleteObject(bucketName, key);
					}
					//				// 文件的etag
					//				String etag = cosObjectSummary.getETag();
					//				// 文件的长度
					//				long fileSize = cosObjectSummary.getSize();
					//				// 文件的存储类型
					//				String storageClasses = cosObjectSummary.getStorageClass();
				}
				String nextMarker = objectListing.getNextMarker();
				listObjectsRequest.setMarker(nextMarker);
				log.info("batch mv end ->" + batchCount);
				batchCount++;
			} while (objectListing.isTruncated());
		} catch (InterruptedException ex) {
			throw new RuntimeException(ex);
		}finally{
			releaseClient(cosClient);
		}

	}

	public static void renameAllBuckets() {
		for (String bucket : BUCKET_NAMES) {
			System.out.println("renameAllObjects -> " + bucket);
			renameAllObjects(bucket);
		}
	}

	public static void renameAllObjects(String bucketName) {
		COSClient cosClient = null;
		try {
			cosClient = getInstance();
			// bucket名需包含appid
			//String bucketName = "yzkj-dzyxdagl-1255000016";
			ListObjectsRequest listObjectsRequest = new ListObjectsRequest();
			// 设置bucket名称
			listObjectsRequest.setBucketName(bucketName);
			// prefix表示列出的object的key以prefix开始
			//listObjectsRequest.setPrefix("/gfs/");
			// deliter表示分隔符, 设置为/表示列出当前目录下的object, 设置为空表示列出所有的object
			listObjectsRequest.setDelimiter("");
			// 设置最大遍历出多少个对象, 一次listobject最大支持1000
			listObjectsRequest.setMaxKeys(5000);
			ObjectListing objectListing = null;
			int batchCount = 1;
			do {
				log.info("batch mv start ->" + batchCount);
				try {
					objectListing = cosClient.listObjects(listObjectsRequest);
				} catch (CosServiceException e) {
					e.printStackTrace();
					return;
				} catch (CosClientException e) {
					e.printStackTrace();
					return;
				}
				// common prefix表示表示被delimiter截断的路径, 如delimter设置为/, common prefix则表示所有子目录的路径
				List<String> commonPrefixs = objectListing.getCommonPrefixes();
				// object summary表示所有列出的object列表
				List<COSObjectSummary> cosObjectSummaries = objectListing.getObjectSummaries();
				for (COSObjectSummary cosObjectSummary : cosObjectSummaries) {
					// 文件的路径key
					String key = cosObjectSummary.getKey();
					
					//				if (!key.contains("gfs/")) {
					//					continue;
					//				}
					if (!key.contains("gfs/wjid")) {
						continue;
					}
					if (key.length() <= 62) {
						continue;
					}
					String keyDest = key;
					if (!key.startsWith("/")) {
						keyDest = "/" + key;
					}
					keyDest = regenerateDfsPathByWjidShort(keyDest);
					String bucketNameDest = getBucketName(keyDest);
					if (bucketNameDest.equals(bucketName)) {
						com.qcloud.cos.model.RenameRequest renameRequest = new RenameRequest(bucketName, key, keyDest);
						cosClient.rename(renameRequest);
						continue;
					}
					System.out.println(key + " ->\n" + keyDest);
					System.out.println(bucketName + " -> " + bucketNameDest);
					cosClient.copyObject(bucketName,
							key, bucketNameDest, keyDest);
					//System.out.println("copyObjectResult:" + copyObjectResult);
					ObjectMetadata objectMetadata = cosClient.getObjectMetadata(bucketNameDest, keyDest);
					
					if (objectMetadata != null && objectMetadata.getContentLength() > 0) {
						cosClient.deleteObject(bucketName, key);
					}
					//				// 文件的etag
					//				String etag = cosObjectSummary.getETag();
					//				// 文件的长度
					//				long fileSize = cosObjectSummary.getSize();
					//				// 文件的存储类型
					//				String storageClasses = cosObjectSummary.getStorageClass();
				}
				String nextMarker = objectListing.getNextMarker();
				listObjectsRequest.setMarker(nextMarker);
				log.info("batch mv end ->" + batchCount);
				batchCount++;
			} while (objectListing.isTruncated());
		} catch (InterruptedException ex) {
			throw new RuntimeException(ex);
		}finally{
			releaseClient(cosClient);
		}

	}

	/**
	 * 从资料明细生成分布式文件系统存储路径-短目录，节省目录元数据空间
	 * 以免glusterfs创建不了目录
	 * 
	 * @param path
	 * @return
	 */
	public static String regenerateDfsPathByWjidShort(String path) {
		String wjid = path.substring(path.lastIndexOf("/") + 1);
		StringBuilder sb = new StringBuilder("/gfs/wjid/");
		try {
			sb.append(wjid.substring(0, 3)).append('/');
			sb.append(wjid.substring(3, 6)).append('/');
			sb.append(wjid.substring(6, 10)).append('/');
			sb.append(wjid.substring(10, 15)).append('/');
			sb.append(wjid.substring(15, 21)).append('/');
			sb.append(wjid);
		} catch (Exception e) {
			log.error("invalid wjid -> " + wjid);
		}
		return sb.toString();
	}
}
