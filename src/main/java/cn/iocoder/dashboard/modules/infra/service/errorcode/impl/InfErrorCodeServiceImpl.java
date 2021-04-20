package cn.iocoder.dashboard.modules.infra.service.errorcode.impl;

import cn.hutool.core.collection.CollUtil;
import cn.iocoder.dashboard.common.pojo.PageResult;
import cn.iocoder.dashboard.framework.errorcode.core.dto.ErrorCodeAutoGenerateReqDTO;
import cn.iocoder.dashboard.framework.errorcode.core.dto.ErrorCodeRespDTO;
import cn.iocoder.dashboard.modules.infra.controller.errorcode.vo.InfErrorCodeCreateReqVO;
import cn.iocoder.dashboard.modules.infra.controller.errorcode.vo.InfErrorCodeExportReqVO;
import cn.iocoder.dashboard.modules.infra.controller.errorcode.vo.InfErrorCodePageReqVO;
import cn.iocoder.dashboard.modules.infra.controller.errorcode.vo.InfErrorCodeUpdateReqVO;
import cn.iocoder.dashboard.modules.infra.convert.errorcode.InfErrorCodeConvert;
import cn.iocoder.dashboard.modules.infra.dal.dataobject.errorcode.InfErrorCodeDO;
import cn.iocoder.dashboard.modules.infra.dal.mysql.errorcode.InfErrorCodeMapper;
import cn.iocoder.dashboard.modules.infra.enums.errorcode.InfErrorCodeTypeEnum;
import cn.iocoder.dashboard.modules.infra.service.errorcode.InfErrorCodeService;
import com.google.common.annotations.VisibleForTesting;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.validation.annotation.Validated;

import javax.annotation.Resource;
import java.util.Date;
import java.util.List;
import java.util.Map;

import static cn.iocoder.dashboard.common.exception.util.ServiceExceptionUtil.exception;
import static cn.iocoder.dashboard.modules.infra.enums.InfErrorCodeConstants.ERROR_CODE_DUPLICATE;
import static cn.iocoder.dashboard.modules.infra.enums.InfErrorCodeConstants.ERROR_CODE_NOT_EXISTS;
import static cn.iocoder.dashboard.util.collection.CollectionUtils.convertMap;
import static cn.iocoder.dashboard.util.collection.CollectionUtils.convertSet;

/**
 * 错误码 Service 实现类
 */
@Service
@Validated
@Slf4j
public class InfErrorCodeServiceImpl implements InfErrorCodeService {

    @Resource
    private InfErrorCodeMapper errorCodeMapper;

    @Override
    public Long createErrorCode(InfErrorCodeCreateReqVO createReqVO) {
        // 校验 code 重复
        validateCodeDuplicate(createReqVO.getCode(), null);

        // 插入
        InfErrorCodeDO errorCode = InfErrorCodeConvert.INSTANCE.convert(createReqVO)
                .setType(InfErrorCodeTypeEnum.MANUAL_OPERATION.getType());
        errorCodeMapper.insert(errorCode);
        // 返回
        return errorCode.getId();
    }

    @Override
    public void updateErrorCode(InfErrorCodeUpdateReqVO updateReqVO) {
        // 校验存在
        this.validateErrorCodeExists(updateReqVO.getId());
        // 校验 code 重复
        validateCodeDuplicate(updateReqVO.getCode(), updateReqVO.getId());

        // 更新
        InfErrorCodeDO updateObj = InfErrorCodeConvert.INSTANCE.convert(updateReqVO)
                .setType(InfErrorCodeTypeEnum.MANUAL_OPERATION.getType());
        errorCodeMapper.updateById(updateObj);
    }

    @Override
    public void deleteErrorCode(Long id) {
        // 校验存在
        this.validateErrorCodeExists(id);
        // 删除
        errorCodeMapper.deleteById(id);
    }

    /**
     * 校验错误码的唯一字段是否重复
     *
     * 是否存在相同编码的错误码
     *
     * @param code 错误码编码
     * @param id 错误码编号
     */
    @VisibleForTesting
    public void validateCodeDuplicate(Integer code, Long id) {
        InfErrorCodeDO errorCodeDO = errorCodeMapper.selectByCode(code);
        if (errorCodeDO == null) {
            return;
        }
        // 如果 id 为空，说明不用比较是否为相同 id 的错误码
        if (id == null) {
            throw exception(ERROR_CODE_DUPLICATE);
        }
        if (!errorCodeDO.getId().equals(id)) {
            throw exception(ERROR_CODE_DUPLICATE);
        }
    }

    @VisibleForTesting
    public void validateErrorCodeExists(Long id) {
        if (errorCodeMapper.selectById(id) == null) {
            throw exception(ERROR_CODE_NOT_EXISTS);
        }
    }

    @Override
    public InfErrorCodeDO getErrorCode(Long id) {
        return errorCodeMapper.selectById(id);
    }

    @Override
    public PageResult<InfErrorCodeDO> getErrorCodePage(InfErrorCodePageReqVO pageReqVO) {
        return errorCodeMapper.selectPage(pageReqVO);
    }

    @Override
    public List<InfErrorCodeDO> getErrorCodeList(InfErrorCodeExportReqVO exportReqVO) {
        return errorCodeMapper.selectList(exportReqVO);
    }

    @Override
    @Transactional
    public void autoGenerateErrorCodes(List<ErrorCodeAutoGenerateReqDTO> autoGenerateDTOs) {
        if (CollUtil.isEmpty(autoGenerateDTOs)) {
            return;
        }
        // 获得错误码
        List<InfErrorCodeDO> errorCodeDOs = errorCodeMapper.selectListByCodes(
                convertSet(autoGenerateDTOs, ErrorCodeAutoGenerateReqDTO::getCode));
        Map<Integer, InfErrorCodeDO> errorCodeDOMap = convertMap(errorCodeDOs, InfErrorCodeDO::getCode);

        // 遍历 autoGenerateBOs 数组，逐个插入或更新。考虑到每次量级不大，就不走批量了
        autoGenerateDTOs.forEach(autoGenerateDTO -> {
            InfErrorCodeDO errorCodeDO = errorCodeDOMap.get(autoGenerateDTO.getCode());
            // 不存在，则进行新增
            if (errorCodeDO == null) {
                errorCodeDO = InfErrorCodeConvert.INSTANCE.convert(autoGenerateDTO)
                        .setType(InfErrorCodeTypeEnum.AUTO_GENERATION.getType());
                errorCodeMapper.insert(errorCodeDO);
                return;
            }
            // 存在，则进行更新。更新有三个前置条件：
            // 条件 1. 只更新自动生成的错误码，即 Type 为 ErrorCodeTypeEnum.AUTO_GENERATION
            if (!InfErrorCodeTypeEnum.AUTO_GENERATION.getType().equals(errorCodeDO.getType())) {
                return;
            }
            // 条件 2. 分组 group 必须匹配，避免存在错误码冲突的情况
            if (!autoGenerateDTO.getApplicationName().equals(errorCodeDO.getApplicationName())) {
                log.error("[autoGenerateErrorCodes][自动创建({}/{}) 错误码失败，数据库中已经存在({}/{})]",
                        autoGenerateDTO.getCode(), autoGenerateDTO.getApplicationName(),
                        errorCodeDO.getCode(), errorCodeDO.getApplicationName());
                return;
            }
            // 条件 3. 错误提示语存在差异
            if (autoGenerateDTO.getMessage().equals(errorCodeDO.getMessage())) {
                return;
            }
            // 最终匹配，进行更新
            errorCodeMapper.updateById(new InfErrorCodeDO().setId(errorCodeDO.getId()).setMessage(autoGenerateDTO.getMessage()));
        });
    }

    @Override
    public List<ErrorCodeRespDTO> getErrorCodeList(String applicationName, Date minUpdateTime) {
        List<InfErrorCodeDO> errorCodeDOs = errorCodeMapper.selectListByApplicationNameAndUpdateTimeGt(
                applicationName, minUpdateTime);
        return InfErrorCodeConvert.INSTANCE.convertList03(errorCodeDOs);
    }

}

