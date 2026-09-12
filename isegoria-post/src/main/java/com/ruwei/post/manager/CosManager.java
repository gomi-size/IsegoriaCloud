package com.ruwei.post.manager;

import cn.hutool.core.util.StrUtil;
import com.qcloud.cos.COSClient;
import com.qcloud.cos.exception.CosClientException;
import com.qcloud.cos.model.GetObjectRequest;
import com.qcloud.cos.model.ObjectMetadata;
import com.qcloud.cos.model.PutObjectRequest;
import com.qcloud.cos.model.PutObjectResult;
import com.qcloud.cos.model.ciModel.persistence.CIObject;
import com.qcloud.cos.model.ciModel.persistence.CIUploadResult;
import com.qcloud.cos.model.ciModel.persistence.ImageInfo;
import com.qcloud.cos.model.ciModel.persistence.OriginalInfo;
import com.qcloud.cos.model.ciModel.persistence.PicOperations;
import com.ruwei.post.config.CosClientConfig;
import jakarta.annotation.Resource;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.util.List;

/**
 * 通用的文件处理类（COS 实现）
 */
@Component
@ConditionalOnProperty(prefix = "storage", name = "type", havingValue = "cos", matchIfMissing = true)
public class CosManager implements ObjectStorageManager {

    private static final String WEBP_FORMAT_RULE = "imageMogr2/format/webp";

    @Resource
    private CosClientConfig cosClientConfig;

    @Resource
    private COSClient cosClient;

    @Override
    public UploadResult putObject(String key, File file) {
        PutObjectRequest putObjectRequest = new PutObjectRequest(cosClientConfig.getBucket(), key,
                file);
        cosClient.putObject(putObjectRequest);
        return buildMinimalResult(key, file.length());
    }

    /**
     * 下载对象
     *
     * @param key 唯一键（是文件夹加上文件的名字）
     */
    @Override
    public InputStream getObject(String key) {
        GetObjectRequest getObjectRequest = new GetObjectRequest(cosClientConfig.getBucket(), key);
        return cosClient.getObject(getObjectRequest).getObjectContent();
    }

    /**
     * 上传图片：上传原始图并生成 webp 展示图，返回 COS 上传结果。
     * GIF 动图不做 webp 转换，保留原图。
     *
     * @param key         COS 对象 key（服务端生成，不允许使用前端文件名）
     * @param bytes       图片字节
     * @param contentType 图片 MIME 类型
     */
    @Override
    public UploadResult uploadImage(String key, byte[] bytes, String contentType) {
        ObjectMetadata metadata = new ObjectMetadata();
        metadata.setContentLength(bytes.length);
        metadata.setContentType(contentType);
        PutObjectRequest request = new PutObjectRequest(cosClientConfig.getBucket(), key,
                new ByteArrayInputStream(bytes), metadata);

        String webpKey = null;
        if (!"image/gif".equalsIgnoreCase(contentType)) {
            webpKey = toWebpKey(key);
            PicOperations picOperations = new PicOperations();
            picOperations.setIsPicInfo(1);
            PicOperations.Rule rule = new PicOperations.Rule();
            rule.setFileId(webpKey);
            rule.setRule(WEBP_FORMAT_RULE);
            rule.setBucket(cosClientConfig.getBucket());
            picOperations.setRules(List.of(rule));
            request.setPicOperations(picOperations);
        }

        PutObjectResult result = cosClient.putObject(request);
        return buildUploadResult(key, webpKey, bytes, contentType, result);
    }

    /**
     * 上传对象（附带图片信息）
     *
     * @param key  唯一键
     * @param file 文件
     *             必须要添加图片处理操作否则会报错
     */
    @Override
    public UploadResult putPictureObject(String key, File file) {
        PutObjectRequest putObjectRequest = new PutObjectRequest(cosClientConfig.getBucket(), key,
                file);
        //（获取基本信息也被视为一种处理）picOperations图片处理对象
        // 对图片进行处理
        PicOperations picOperations = new PicOperations();
        // 1 表示返回原图信息
        picOperations.setIsPicInfo(1);

        //可能会有多个规则，利用一个集合，进行存储
        List<PicOperations.Rule> ruleList = new java.util.ArrayList<>();
        //一，文件压缩（转化为webp格式）
        String webpKey = toWebpKey(key);
        //设置参数
        PicOperations.Rule compressRule = new PicOperations.Rule();
        compressRule.setFileId(webpKey);
        compressRule.setRule(WEBP_FORMAT_RULE);
        compressRule.setBucket(cosClientConfig.getBucket());
        ruleList.add(compressRule);
        //将规则设置到图片处理规则中
        picOperations.setRules(ruleList);
        //图片处理规则putObjectRequest把图片处理对象放入到这个类中
        putObjectRequest.setPicOperations(picOperations);

        cosClient.putObject(putObjectRequest);
        return new UploadResult(key, webpKey, buildUrl(key), buildUrl(webpKey), null, null,
                file.length(), null);
    }


    /**
     * 删除对象
     *
     * @param key 文件 key
     */
    @Override
    public void deleteObject(String key) throws CosClientException {
        cosClient.deleteObject(cosClientConfig.getBucket(), key);
    }


    private UploadResult buildUploadResult(String key, String webpKey, byte[] bytes,
                                           String contentType, PutObjectResult result) {
        String displayKey = webpKey != null ? webpKey : key;
        String originalUrl = buildUrl(key);
        String displayUrl = buildUrl(displayKey);
        Integer width = null;
        Integer height = null;

        CIUploadResult ci = result.getCiUploadResult();
        if (ci != null && ci.getOriginalInfo() != null) {
            OriginalInfo originalInfo = ci.getOriginalInfo();
            ImageInfo imageInfo = originalInfo.getImageInfo();
            if (imageInfo != null) {
                width = imageInfo.getWidth();
                height = imageInfo.getHeight();
            }
            if (StrUtil.isNotBlank(originalInfo.getLocation())) {
                // COS 返回的 location 通常不带协议头（host/path 形式），补全 https:// 供前端直接使用
                originalUrl = ensureScheme(originalInfo.getLocation());
            }
            List<CIObject> processedObjects = ci.getProcessResults() == null ? null
                    : ci.getProcessResults().getObjectList();
            if (processedObjects != null && !processedObjects.isEmpty()) {
                CIObject processed = processedObjects.get(0);
                if (processed.getWidth() != null) {
                    width = processed.getWidth();
                }
                if (processed.getHeight() != null) {
                    height = processed.getHeight();
                }
                if (StrUtil.isNotBlank(processed.getLocation())) {
                    displayUrl = ensureScheme(processed.getLocation());
                }
            }
        }

        // COS 未返回图片信息时，用本地解码兜底（gif/jpg/png 可读，webp 可能拿不到）
        if (width == null || height == null) {
            ImageSize size = readImageSize(bytes);
            if (size != null) {
                width = size.width;
                height = size.height;
            }
        }

        return new UploadResult(key, displayKey, originalUrl, displayUrl, width, height,
                (long) bytes.length, contentType);
    }

    private UploadResult buildMinimalResult(String key, long size) {
        return new UploadResult(key, key, buildUrl(key), buildUrl(key), null, null, size, null);
    }

    private String buildUrl(String key) {
        String host = StrUtil.removeSuffix(cosClientConfig.getHost(), "/");
        return host + "/" + key;
    }

    /**
     * 补全 URL 协议头：COS 的 location 字段通常返回不带协议的地址（host/path 形式），
     * 前端 <img> 直接使用会解析失败，统一补 https://；已带协议的保持不变。
     */
    private String ensureScheme(String url) {
        if (url.startsWith("http://") || url.startsWith("https://")) {
            return url;
        }
        return "https://" + url;
    }

    private String toWebpKey(String key) {
        int dot = key.lastIndexOf('.');
        return dot < 0 ? key + ".webp" : key.substring(0, dot) + ".webp";
    }

    private ImageSize readImageSize(byte[] bytes) {
        try (ByteArrayInputStream in = new ByteArrayInputStream(bytes)) {
            BufferedImage image = ImageIO.read(in);
            return image == null ? null : new ImageSize(image.getWidth(), image.getHeight());
        } catch (IOException e) {
            return null;
        }
    }

    private record ImageSize(int width, int height) {
    }
}
