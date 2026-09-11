package com.ruwei.domain.dto;

import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;

import java.io.Serializable;
import java.math.BigDecimal;
import java.util.List;

/**
 * 创建帖子的请求类
 *
 * <p>作者（userId）不由此 DTO 传入，统一取当前登录态内部 id（= Sa-Token loginId），
 * 防止前端伪造作者。</p>
 */
@Data
public class PostDTO implements Serializable {

    /**
     * 帖子内部主键（编辑时必传；创建时忽略）
     */
    private Long id;

    /**
     * 草稿记录 id（仅 {@code POST /post/publishDraft} 使用）：
     * 标识要发布的草稿，后端按草稿的 draftOfId 决定「更新原帖送审」还是「新建送审」。
     */
    private Long draftId;

    /**
     * 对外唯一编码
     */
    private String postCode;

    /**
     * 所属板块（可为空）
     */
    private Long boardId;

    /**
     * 标题（不能为空）
     */
    private String title;

    /**
     * 正文(发布时过敏感词 filter)（）
     */
    private String content;

    /**
     * 封面图URL（不能为空）
     */
    private String cover;

    /**
     * 1图文 2视频 3纯文
     */
    private Integer type;

    /**
     * 视频地址
     */
    private String videoUrl;

    /**
     * 图片地址集合（一次可传多个图片 URL，创建/编辑时全量写入 post_image 表）
     */
    private List<String> imageUrl;

    /**
     * 结构化内容块（图文混排）：前端按数组顺序渲染，实现「文字与图片交错排列」。
     *
     * <p>与 {@code content} / {@code imageUrl} <b>互斥优先</b>——传入 contentBlocks 时以其为准；
     * 为空时回退老逻辑（纯文本正文 + post_image 图集），保证旧客户端兼容。</p>
     */
    private List<ContentBlock> contentBlocks;

    /**
     * 话题（传递的 tag 的 id 列表，标签须已存在且未被禁用）
     */
    private List<Long> topicList;

    /**
     * 可见性：前端传<b>文字</b>（"公开" / "仅粉丝可见" / "私密"），
     * 后端经 {@code PostVisibilityEnum} 转成整数（1/2/3）落库。
     */
    private String Visibility;

    /**
     * 生命周期状态：前端传<b>文字</b>，后端经 {@code PostStatusEnum} 转成整数落库。
     *
     * <p><b>编辑时仅「草稿」一个取值有效</b>：传 "草稿" 表示另存为草稿（不走审核），
     * 其余取值一律被忽略并强制送审（status=审核中）。生命周期由创建/编辑/审核流程单向推进，
     * 作者不能借此字段把帖子直接指定为「已发布」绕过审核。</p>
     */
    private String Status;

    /**
     * 审核结果：前端传<b>文字</b>（"待审" / "通过" / "驳回"）。
     *
     * <p><b>仅管理端审核流可写</b>，普通用户创建/编辑时传入无效（后端强制置为「待审」）。</p>
     */
    private String AuditStatus;

    /**
     * 置顶(重排强插第1/2位)
     */
    private Integer isTop;

    /**
     * 纬度
     */
    private BigDecimal latitude;

    /**
     * 经度
     */
    private BigDecimal longitude;

    /**
     * 位置名称
     */
    private String locationName;


    @TableField(exist = false)
    private static final long serialVersionUID = 1L;
}