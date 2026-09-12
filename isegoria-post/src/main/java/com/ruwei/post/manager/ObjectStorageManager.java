package com.ruwei.post.manager;

import java.io.File;
import java.io.InputStream;

/**
 * 对象存储抽象接口。
 * 当前唯一实现为 {@link CosManager}，通过 yml 中 storage.type=cos 激活（见实现类上的 @ConditionalOnProperty）。
 */
public interface ObjectStorageManager {

    /**
     * 上传图片：上传原始图并生成 webp 展示图，返回上传结果。
     * GIF 动图不做 webp 转换，保留原图。
     *
     * @param key         对象 key（服务端生成，不允许使用前端文件名）
     * @param bytes       图片字节
     * @param contentType 图片 MIME 类型
     */
    UploadResult uploadImage(String key, byte[] bytes, String contentType) throws Exception;

    /**
     * 上传对象（不处理图片，按原样上传）
     *
     * @param key  唯一键（是文件夹加上文件的名字）
     * @param file 文件
     */
    UploadResult putObject(String key, File file) throws Exception;

    /**
     * 上传对象（附带图片处理：生成 webp 展示图）
     *
     * @param key  唯一键
     * @param file 文件
     */
    UploadResult putPictureObject(String key, File file) throws Exception;

    /**
     * 下载对象
     *
     * @param key 唯一键（是文件夹加上文件的名字）
     */
    InputStream getObject(String key) throws Exception;

    /**
     * 删除对象
     *
     * @param key 文件 key
     */
    void deleteObject(String key) throws Exception;
}
