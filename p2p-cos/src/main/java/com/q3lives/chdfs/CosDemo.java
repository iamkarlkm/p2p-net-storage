/*
 * Click nbfs://nbhost/SystemFileSystem/Templates/Licenses/license-default.txt to change this license
 * Click nbfs://nbhost/SystemFileSystem/Templates/Classes/Class.java to edit this template
 */

package com.q3lives.chdfs;
import java.util.LinkedList;
import java.util.List;

import com.qcloud.cos.COSClient;
import com.qcloud.cos.ClientConfig;
import com.qcloud.cos.auth.BasicCOSCredentials;
import com.qcloud.cos.auth.COSCredentials;
import com.qcloud.cos.exception.CosClientException;
import com.qcloud.cos.exception.CosServiceException;
import com.qcloud.cos.exception.MultiObjectDeleteException;
import com.qcloud.cos.exception.MultiObjectDeleteException.DeleteError;
import com.qcloud.cos.model.Bucket;
import com.qcloud.cos.model.BucketLoggingConfiguration;
import com.qcloud.cos.model.BucketTaggingConfiguration;
import com.qcloud.cos.model.BucketVersioningConfiguration;
import com.qcloud.cos.model.COSObject;
import com.qcloud.cos.model.CannedAccessControlList;
import com.qcloud.cos.model.CreateBucketRequest;
import com.qcloud.cos.model.DeleteObjectsRequest;
import com.qcloud.cos.model.DeleteObjectsRequest.KeyVersion;
import com.qcloud.cos.model.DeleteObjectsResult;
import com.qcloud.cos.model.DeleteObjectsResult.DeletedObject;
import com.qcloud.cos.model.GetObjectRequest;
import com.qcloud.cos.model.ObjectMetadata;
import com.qcloud.cos.model.PutObjectRequest;
import com.qcloud.cos.model.PutObjectResult;
import com.qcloud.cos.model.SetBucketLoggingConfigurationRequest;
import com.qcloud.cos.model.SetBucketTaggingConfigurationRequest;
import com.qcloud.cos.model.SetBucketVersioningConfigurationRequest;
import com.qcloud.cos.model.TagSet;
import com.qcloud.cos.region.Region;
import java.io.File;
import java.util.ArrayList;
import java.util.Date;

/**
 * 展示了创建bucket, 删除bucket, 查询bucket是否存在的demo
 *
 */
/**
 *
 * @author karl
 */
public class CosDemo {

	// 创建bucket
    public static void CreateBucketDemo() {
        // 1 初始化用户身份信息(appid, secretId, secretKey)
        COSCredentials cred = new BasicCOSCredentials("AKIDXXXXXXXX", "1A2Z3YYYYYYYYYY");
        // 2 设置bucket的区域, COS地域的简称请参照 https://www.qcloud.com/document/product/436/6224
        ClientConfig clientConfig = new ClientConfig(new Region("ap-beijing-1"));
        // 3 生成cos客户端
        COSClient cosclient = new COSClient(cred, clientConfig);
        // bucket名称, 需包含appid
        String bucketName = "publicreadbucket-1251668577";
        
        CreateBucketRequest createBucketRequest = new CreateBucketRequest(bucketName);
        // 设置bucket的权限为PublicRead(公有读私有写), 其他可选有私有读写, 公有读私有写
        createBucketRequest.setCannedAcl(CannedAccessControlList.PublicRead);
        Bucket bucket = cosclient.createBucket(createBucketRequest);
        
        // 关闭客户端
        cosclient.shutdown();
    }

    // 开启 bucket 版本控制
    public static void SetBucketVersioning() {
        // 1 初始化用户身份信息(appid, secretId, secretKey)
        COSCredentials cred = new BasicCOSCredentials("AKIDXXXXXXXX", "1A2Z3YYYYYYYYYY");
        // 2 设置bucket的区域, COS地域的简称请参照 https://www.qcloud.com/document/product/436/6224
        ClientConfig clientConfig = new ClientConfig(new Region("ap-beijing-1"));
        // 3 生成cos客户端
        COSClient cosclient = new COSClient(cred, clientConfig);
        // bucket名称, 需包含appid
        String bucketName = "examplebucket-1251668577";

        // 开启版本控制
        BucketVersioningConfiguration bucketVersioningConfiguration = new BucketVersioningConfiguration(BucketVersioningConfiguration.ENABLED);
        // 关闭版本控制
        //BucketVersioningConfiguration bucketVersioningConfiguration = new BucketVersioningConfiguration(BucketVersioningConfiguration.SUSPENDED);
        SetBucketVersioningConfigurationRequest setBucketVersioningConfigurationRequest = new SetBucketVersioningConfigurationRequest(bucketName, bucketVersioningConfiguration);
        cosclient.setBucketVersioningConfiguration(setBucketVersioningConfigurationRequest);

        cosclient.shutdown();
    }

    // 开启日志存储
    public static void SetBucketLogging() {
        // 1 初始化用户身份信息(appid, secretId, secretKey)
        COSCredentials cred = new BasicCOSCredentials("AKIDXXXXXXXX", "1A2Z3YYYYYYYYYY");
        // 2 设置bucket的区域, COS地域的简称请参照 https://www.qcloud.com/document/product/436/6224
        ClientConfig clientConfig = new ClientConfig(new Region("ap-beijing-1"));
        // 3 生成cos客户端
        COSClient cosclient = new COSClient(cred, clientConfig);
        // bucket名称, 需包含appid
        String bucketName = "examplebucket-1251668577";

        BucketLoggingConfiguration bucketLoggingConfiguration = new BucketLoggingConfiguration();
        // 设置日志存储的 bucket
        bucketLoggingConfiguration.setDestinationBucketName(bucketName);
        // 设置日志存储的前缀
        bucketLoggingConfiguration.setLogFilePrefix("logs/");
        SetBucketLoggingConfigurationRequest setBucketLoggingConfigurationRequest =
                new SetBucketLoggingConfigurationRequest(bucketName, bucketLoggingConfiguration);
        cosclient.setBucketLoggingConfiguration(setBucketLoggingConfigurationRequest);
    }

    // 使用 bucket tag
    public static void SetGetDeleteBucketTagging() {
        // 1 初始化用户身份信息(secretId, secretKey)
        COSCredentials cred = new BasicCOSCredentials("AKIDXXXXXXXX", "1A2Z3YYYYYYYYYY");
        // 2 设置bucket的区域, COS地域的简称请参照 https://www.qcloud.com/document/product/436/6224
        ClientConfig clientConfig = new ClientConfig(new Region("ap-guangzhou"));
        // 3 生成cos客户端
        COSClient cosclient = new COSClient(cred, clientConfig);
        // bucket名需包含appid
        String bucketName = "mybucket-1251668577";
        List<TagSet> tagSetList = new LinkedList<TagSet>();
        TagSet tagSet = new TagSet();
        tagSet.setTag("age", "18");
        tagSet.setTag("name", "xiaoming");
        tagSetList.add(tagSet);
        BucketTaggingConfiguration bucketTaggingConfiguration = new BucketTaggingConfiguration();
        bucketTaggingConfiguration.setTagSets(tagSetList);
        SetBucketTaggingConfigurationRequest setBucketTaggingConfigurationRequest =
                new SetBucketTaggingConfigurationRequest(bucketName, bucketTaggingConfiguration);
        cosclient.setBucketTaggingConfiguration(setBucketTaggingConfigurationRequest);

        cosclient.getBucketTaggingConfiguration(bucketName);
        cosclient.deleteBucketTaggingConfiguration(bucketName);
    }
    
    // 删除bucket, 只用于空bucket, 含有数据的bucket需要在删除前清空删除。
    public static void DeleteBucketDemo() {
        // 1 初始化用户身份信息(appid, secretId, secretKey)
        COSCredentials cred = new BasicCOSCredentials("AKIDXXXXXXXX", "1A2Z3YYYYYYYYYY");
        // 2 设置bucket的区域, COS地域的简称请参照 https://www.qcloud.com/document/product/436/6224
        ClientConfig clientConfig = new ClientConfig(new Region("ap-beijing-1"));
        // 3 生成cos客户端
        COSClient cosclient = new COSClient(cred, clientConfig);
        // bucket名称, 需包含appid        
        String bucketName = "publicreadbucket-1251668577";
        // 删除bucket
        cosclient.deleteBucket(bucketName);
        
        // 关闭客户端
        cosclient.shutdown();
    }
    
    // 查询bucket是否存在
    public static void JudgeBucketExistDemo() {
        // 1 初始化用户身份信息(appid, secretId, secretKey)
        COSCredentials cred = new BasicCOSCredentials("AKIDXXXXXXXX", "1A2Z3YYYYYYYYYY");
        // 2 设置bucket的区域, COS地域的简称请参照 https://www.qcloud.com/document/product/436/6224
        ClientConfig clientConfig = new ClientConfig(new Region("ap-beijing-1"));
        // 3 生成cos客户端
        COSClient cosclient = new COSClient(cred, clientConfig);
        
        String bucketName = "publicreadbucket-1251668577";
        // 判断bucket是否存在
        cosclient.doesBucketExist(bucketName);
        
        // 关闭客户端
        cosclient.shutdown();
    }    

    public static void ListBuckets() {
        // 1 初始化用户身份信息(appid, secretId, secretKey)
        COSCredentials cred = new BasicCOSCredentials("AKIDxxxxxxxxxxxxxxxxxxxxxxxxxxxxx", "****************************");
        // 2 设置bucket的区域, COS地域的简称请参照 https://www.qcloud.com/document/product/436/6224
        ClientConfig clientConfig = new ClientConfig(new Region("ap-shanghai"));
        // 3 生成cos客户端
        COSClient cosclient = new COSClient(cred, clientConfig);

        List<Bucket> buckets = cosclient.listBuckets();

        for (Bucket bucket : buckets) {
            System.out.println(bucket.getName());
            System.out.println(bucket.getLocation());
            System.out.println(bucket.getOwner());
            System.out.println(bucket.getType());
            System.out.println(bucket.getBucketType());
        }
    }
	
	public static void GetObjectToFileDemo() {
        // 初始化用户身份信息(secretId, secretKey)
        COSCredentials cred = new BasicCOSCredentials("AKIDXXXXXXXX","1A2Z3YYYYYYYYYY");
        // 设置bucket的区域, COS地域的简称请参照 https://www.qcloud.com/document/product/436/6224
        ClientConfig clientConfig = new ClientConfig(new Region("ap-guangzhou"));
        // 生成cos客户端
        COSClient cosclient = new COSClient(cred, clientConfig);
        String key = "test/my_test.json";
        String bucketName = "mybucket-1251668577";
        boolean useTrafficLimit = false;
        GetObjectRequest getObjectRequest = new GetObjectRequest(bucketName, key);
        if(useTrafficLimit) {
            getObjectRequest.setTrafficLimit(8*1024*1024);
        }
        File localFile = new File("my_test.json");
        ObjectMetadata objectMetadata = cosclient.getObject(getObjectRequest, localFile);
        System.out.println(objectMetadata.getContentLength());
    }
	
	// 删除单个文件(不带版本号, 即bucket未开启多版本)
    public static void DelSingleFile() {
        // 1 初始化用户身份信息(secretId, secretKey)
        COSCredentials cred = new BasicCOSCredentials("AKIDXXXXXXXX", "1A2Z3YYYYYYYYYY");
        // 2 设置bucket的区域, COS地域的简称请参照 https://www.qcloud.com/document/product/436/6224
        ClientConfig clientConfig = new ClientConfig(new Region("ap-beijing-1"));
        // 3 生成cos客户端
        COSClient cosclient = new COSClient(cred, clientConfig);
        // bucket名需包含appid
        String bucketName = "mybucket-1251668577";
        try {
            String key = ""; // 空key值
            cosclient.deleteObject(bucketName, key);
        } catch (CosServiceException e) { // 如果是其他错误, 比如参数错误， 身份验证不过等会抛出CosServiceException
            e.printStackTrace();
        } catch (CosClientException e) { // 如果是客户端错误，比如连接不上COS
            e.printStackTrace();
        } catch(IllegalArgumentException e) { // 该测试用例的预期结果
            e.printStackTrace();
        }

        try {
            String key = "aaa/bbb.txt";
            cosclient.deleteObject(bucketName, key);
        } catch (CosServiceException e) { // 如果是其他错误, 比如参数错误， 身份验证不过等会抛出CosServiceException
            e.printStackTrace();
        } catch (CosClientException e) { // 如果是客户端错误，比如连接不上COS
            e.printStackTrace();
        }
        
        // 关闭客户端
        cosclient.shutdown();
    }

    // 批量删除文件(不带版本号, 即bucket未开启多版本)
    public static void BatchDelFile() {
        // 1 初始化用户身份信息(secretId, secretKey)
        COSCredentials cred = new BasicCOSCredentials("AKIDXXXXXXXX", "1A2Z3YYYYYYYYYY");
        // 2 设置bucket的区域, COS地域的简称请参照 https://www.qcloud.com/document/product/436/6224
        ClientConfig clientConfig = new ClientConfig(new Region("ap-beijing-1"));
        // 3 生成cos客户端
        COSClient cosclient = new COSClient(cred, clientConfig);
        // bucket名需包含appid
        String bucketName = "mybucket-1251668577";

        DeleteObjectsRequest deleteObjectsRequest = new DeleteObjectsRequest(bucketName);
        // 设置要删除的key列表, 最多一次删除1000个
        ArrayList<KeyVersion> keyList = new ArrayList<>();
        // 传入要删除的文件名
        keyList.add(new KeyVersion("aaa.txt"));
        keyList.add(new KeyVersion("bbb.mp4"));
        keyList.add(new KeyVersion("ccc/ddd.jpg"));
        deleteObjectsRequest.setKeys(keyList);

        // 批量删除文件
        try {
            DeleteObjectsResult deleteObjectsResult = cosclient.deleteObjects(deleteObjectsRequest);
            List<DeletedObject> deleteObjectResultArray = deleteObjectsResult.getDeletedObjects();
        } catch (MultiObjectDeleteException mde) { // 如果部分产出成功部分失败, 返回MultiObjectDeleteException
            List<DeletedObject> deleteObjects = mde.getDeletedObjects();
            List<DeleteError> deleteErrors = mde.getErrors();
        } catch (CosServiceException e) { // 如果是其他错误, 比如参数错误， 身份验证不过等会抛出CosServiceException
            e.printStackTrace();
        } catch (CosClientException e) { // 如果是客户端错误，比如连接不上COS
            e.printStackTrace();
        }
        
        // 关闭客户端
        cosclient.shutdown();
    }

    // 批量删除带有版本号的文件(即bucket开启了多版本)
    public static void BatchDelFileWithVersion() {
        // 1 初始化用户身份信息(secretId, secretKey)
        COSCredentials cred = new BasicCOSCredentials("AKIDXXXXXXXX", "1A2Z3YYYYYYYYYY");
        // 2 设置bucket的区域, COS地域的简称请参照 https://www.qcloud.com/document/product/436/6224
        ClientConfig clientConfig = new ClientConfig(new Region("ap-beijing-1"));
        // 3 生成cos客户端
        COSClient cosclient = new COSClient(cred, clientConfig);
        // bucket名需包含appid
        String bucketName = "mybucket-1251668577";

        DeleteObjectsRequest deleteObjectsRequest = new DeleteObjectsRequest(bucketName);
        // 设置要删除的key列表, 最多一次删除1000个
        ArrayList<KeyVersion> keyList = new ArrayList<>();
        // 传入要删除的文件名
        keyList.add(new KeyVersion("aaa.txt", "axbefagagaxxfafa"));
        keyList.add(new KeyVersion("bbb.mp4", "awcafa1faxg0lx"));
        keyList.add(new KeyVersion("ccc/ddd.jpg", "kafa1kxxaa2ymh"));
        deleteObjectsRequest.setKeys(keyList);

        // 批量删除文件
        try {
            DeleteObjectsResult deleteObjectsResult = cosclient.deleteObjects(deleteObjectsRequest);
            List<DeletedObject> deleteObjectResultArray = deleteObjectsResult.getDeletedObjects();
        } catch (MultiObjectDeleteException mde) { // 如果部分产出成功部分失败, 返回MultiObjectDeleteException
            List<DeletedObject> deleteObjects = mde.getDeletedObjects();
            List<DeleteError> deleteErrors = mde.getErrors();
        } catch (CosServiceException e) { // 如果是其他错误, 比如参数错误， 身份验证不过等会抛出CosServiceException
            e.printStackTrace();
        } catch (CosClientException e) { // 如果是客户端错误，比如连接不上COS
            e.printStackTrace();
        }
        
        // 关闭客户端
        cosclient.shutdown();
    }

	static COSClient cosClient = createCli();;

    static COSClient createCli() {
        return createCli("ap-shanghai");
    }

    static COSClient createCli(String region) {
        // 初始化用户身份信息(secretId, secretKey)
        COSCredentials cred = new BasicCOSCredentials("AKIDXXXXXXXX","1A2Z3YYYYYYYYYY");
        // 设置bucket的区域, COS地域的简称请参照 https://www.qcloud.com/document/product/436/6224
        ClientConfig clientConfig = new ClientConfig(new Region(region));
        // 生成cos客户端
        return new COSClient(cred, clientConfig);
    }

    static void putObjectDemo() {
        String bucketName = "examplebucket-1251668577";
        String key = "abc/abc.txt";

        String localPath = "abc.txt";

        ObjectMetadata objectMetadata = new ObjectMetadata();
        objectMetadata.setHeader("expires", new Date(1660000000000L));

        PutObjectRequest putObjectRequest = new PutObjectRequest(bucketName, key, new File(localPath));
        putObjectRequest.withMetadata(objectMetadata);

        PutObjectResult putObjectResult = cosClient.putObject(putObjectRequest);

        System.out.println(putObjectResult.getRequestId());

        GetObjectRequest getObjectRequest = new GetObjectRequest(bucketName, key);
        COSObject cosObject = cosClient.getObject(getObjectRequest);
        System.out.println(cosObject.getObjectMetadata().getRequestId());

        cosClient.shutdown();
    }

    public static void main(String[] args) {
        ListBuckets();
    }
	
	/**
	 * 
// 可以参考下面的例子，结合实际情况做调整
void showTransferProgress(Transfer transfer) {
    System.out.println(transfer.getDescription());


    // transfer.isDone() 查询下载是否已经完成
    while (transfer.isDone() == false) {
        try {
            // 每 2 秒获取一次进度
            Thread.sleep(2000);
        } catch (InterruptedException e) {
            return;
        }


        TransferProgress progress = transfer.getProgress();
        long sofar = progress.getBytesTransferred();
        long total = progress.getTotalBytesToTransfer();
        double pct = progress.getPercentTransferred();
        System.out.printf("upload progress: [%d / %d] = %.02f%%\n", sofar, total, pct);
    }


    // 完成了 Completed，或者失败了 Failed
    System.out.println(transfer.getState());
}

	 */
}
