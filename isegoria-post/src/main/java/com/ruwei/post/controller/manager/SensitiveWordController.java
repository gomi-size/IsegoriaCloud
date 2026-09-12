package com.ruwei.post.controller.manager;

import cn.dev33.satoken.annotation.SaCheckRole;
import com.ruwei.common.core.BaseResponse;
import com.ruwei.common.core.ErrorCode;
import com.ruwei.common.core.ResultUtils;
import com.ruwei.common.core.ThrowUtils;
import com.ruwei.model.dto.SensitiveWordAddDTO;
import com.ruwei.model.vo.SensitiveWordVO;
import com.ruwei.post.service.SensitiveWordService;
import jakarta.annotation.Resource;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * 敏感词管理端接口。
 *
 * <p>提供敏感词的查询、新增（支持单条与批量）、删除能力，需 {@code admin} 角色
 * （通过 {@code @SaCheckRole("admin")} 校验，角色由 {@code user.admin} 字段推导）。
 * 所有写操作成功后内部均会触发内存词库热刷新。</p>
 *
 * @see SensitiveWordService
 */
@RestController
@RequestMapping("/admin/sensitive-words")
@SaCheckRole("admin")
public class SensitiveWordController {

    @Resource
    private SensitiveWordService sensitiveWordService;



/**
 * 获取全部敏感词列表。
 *
 * @return 敏感词 VO 列表
 */
@GetMapping
public BaseResponse<List<SensitiveWordVO>> list() {
    return ResultUtils.success(sensitiveWordService.listAll());
}

/**
 * 批量新增敏感词（一次传多组，前端统一传数组）。
 * <pre>
 * 批量：  [{"word":"代开发票","category":2,"action":2},{"word":"傻子","action":1}]
 * </pre>
 * category / action 缺省时由 {@link SensitiveWordAddDTO} 默认值兜底（分类 1、动作 1）。
 *
 * @param dtos 敏感词入参列表
 * @return 成功写入的条数
 */
@PostMapping
public BaseResponse<Integer> add(@RequestBody List<SensitiveWordAddDTO> dtos) {
    int count = sensitiveWordService.addBatch(dtos);
    ThrowUtils.throwIf(count == 0, ErrorCode.PARAMS_ERROR, "没有有效的敏感词");
    return ResultUtils.success(count);
}



/**
 * 根据主键删除敏感词。
 *
 * @param id 敏感词对应的主键 id
 * @return 删除结果提示
 */
@DeleteMapping("/{id}")
public BaseResponse<String> delete(@PathVariable Long id) {
    ThrowUtils.throwIf(id == null, ErrorCode.PARAMS_ERROR, "id不能为空");
    boolean result = sensitiveWordService.deleteWord(id);
    ThrowUtils.throwIf(!result, ErrorCode.OPERATION_ERROR, "删除失败");
    return ResultUtils.success("删除成功");
}
}
