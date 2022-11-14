package io.metersphere.excel.listener;

import com.alibaba.excel.annotation.ExcelProperty;
import com.alibaba.excel.context.AnalysisContext;
import com.alibaba.excel.event.AnalysisEventListener;
import com.alibaba.excel.util.DateUtils;
import io.metersphere.base.domain.Issues;
import io.metersphere.commons.constants.CustomFieldType;
import io.metersphere.commons.exception.MSException;
import io.metersphere.commons.utils.BeanUtils;
import io.metersphere.commons.utils.CommonBeanFactory;
import io.metersphere.commons.utils.LogUtil;
import io.metersphere.commons.utils.SessionUtils;
import io.metersphere.dto.CustomFieldDao;
import io.metersphere.dto.CustomFieldItemDTO;
import io.metersphere.dto.CustomFieldResourceDTO;
import io.metersphere.excel.constants.IssueExportHeadField;
import io.metersphere.excel.domain.ExcelErrData;
import io.metersphere.excel.domain.IssueExcelData;
import io.metersphere.excel.domain.IssueExcelDataFactory;
import io.metersphere.excel.utils.ExcelImportType;
import io.metersphere.excel.utils.ExcelValidateHelper;
import io.metersphere.i18n.Translator;
import io.metersphere.request.issues.IssueImportRequest;
import io.metersphere.service.IssuesService;
import io.metersphere.xpack.track.dto.request.IssuesUpdateRequest;
import org.apache.commons.collections.CollectionUtils;
import org.apache.commons.collections.MapUtils;
import org.apache.commons.lang3.BooleanUtils;
import org.apache.commons.lang3.StringUtils;

import java.lang.reflect.Field;
import java.util.*;
import java.util.stream.Collectors;

/**
 * 缺陷导入读取
 * @author songcc
 */

public class IssueExcelListener extends AnalysisEventListener<Map<Integer, String>> {

    private Class dataClass;
    private IssueImportRequest request;
    private Boolean isThirdPlatform = false;
    private Map<Integer, String> headMap;
    private List<CustomFieldDao> customFields = new ArrayList<>();
    private IssuesService issuesService;
    /**
     * excel表头字段字典值
     */
    private Map<String, String> headFieldTransferDic = new HashMap<>();
    private Map<String, List<CustomFieldResourceDTO>> issueCustomFieldMap = new HashMap<>();

    /**
     * 每超过2000条数据, 则插入数据库
     */
    protected static final int BATCH_THRESHOLD = 2000;

    /**
     * insertList: 新增缺陷集合
     * updateList: 覆盖缺陷集合
     * errList: 校验失败缺陷集合
     */
    protected List<IssueExcelData> insertList = new ArrayList<>();
    protected List<IssueExcelData> updateList = new ArrayList<>();
    protected List<ExcelErrData<IssueExcelData>> errList = new ArrayList<>();

    public IssueExcelListener(IssueImportRequest request, Class clazz, Boolean isThirdPlatform, List<CustomFieldDao> customFields) {
        this.request = request;
        this.dataClass = clazz;
        this.isThirdPlatform = isThirdPlatform;
        this.customFields = customFields;
        this.issuesService = CommonBeanFactory.getBean(IssuesService.class);
    }

    @Override
    public void invoke(Map<Integer, String> data, AnalysisContext analysisContext) {
        Integer rowIndex = analysisContext.readRowHolder().getRowIndex();
        IssueExcelData issueExcelData = null;
        StringBuilder errMsg;
        try {
            issueExcelData = this.parseDataToModel(data);
            // EXCEL校验, 如果不是第三方模板则需要校验
            errMsg = new StringBuilder(!isThirdPlatform ? ExcelValidateHelper.validateEntity(issueExcelData) : StringUtils.EMPTY);
            //自定义校验规则
            if (StringUtils.isEmpty(errMsg)) {
                validate(issueExcelData, errMsg);
            }
        } catch (Exception e) {
            errMsg = new StringBuilder(Translator.get("parse_data_error"));
            LogUtil.error(e.getMessage(), e);
        }

        if (StringUtils.isNotEmpty(errMsg)) {
            ExcelErrData excelErrData = new ExcelErrData(issueExcelData, rowIndex,
                    Translator.get("number")
                            .concat(StringUtils.SPACE)
                            .concat(String.valueOf(rowIndex + 1)).concat(StringUtils.SPACE)
                            .concat(Translator.get("row"))
                            .concat(Translator.get("error"))
                            .concat("：")
                            .concat(errMsg.toString()));
            errList.add(excelErrData);
        } else {
            if (issueExcelData.getNum() == null) {
                // ID为空或不存在, 新增
                issueExcelData.setAddFlag(Boolean.TRUE);
                insertList.add(issueExcelData);
            } else {
                Issues issues = checkIssueExist(issueExcelData.getNum(), request.getProjectId());
                if (issues == null) {
                    // ID列值不存在, 则新增
                    issueExcelData.setAddFlag(Boolean.TRUE);
                    insertList.add(issueExcelData);
                } else {
                    // ID存在
                    if (StringUtils.equals(request.getImportType(), ExcelImportType.Update.name())) {
                        // 覆盖模式
                        issueExcelData.setId(issues.getId());
                        issueExcelData.setAddFlag(Boolean.FALSE);
                        updateList.add(issueExcelData);
                    }
                }
            }
        }
        if (insertList.size() > BATCH_THRESHOLD || updateList.size() > BATCH_THRESHOLD) {
            saveData();
            insertList.clear();
            updateList.clear();
        }
    }

    public void saveData() {
        //excel中用例都有错误时就返回，只要有用例可用于更新或者插入就不返回
        if (!errList.isEmpty()) {
            return;
        }

        if (CollectionUtils.isEmpty(insertList) && CollectionUtils.isEmpty(updateList)) {
            MSException.throwException(Translator.get("no_legitimate_issue_tip"));
        }

        if (CollectionUtils.isNotEmpty(insertList)) {
            List<IssuesUpdateRequest> issues = insertList.stream().map(item -> this.convertToIssue(item)).collect(Collectors.toList());
            issuesService.saveImportData(issues);
        }

        if (CollectionUtils.isNotEmpty(updateList)) {
            List<IssuesUpdateRequest> issues = updateList.stream().map(item -> this.convertToIssue(item)).collect(Collectors.toList());
            issuesService.updateImportData(issues);
        }
    }

    @Override
    public void invokeHeadMap(Map<Integer, String> headMap, AnalysisContext context) {
        this.headMap = headMap;
        this.genExcelHeadFieldTransferDic();
        this.formatHeadMap();
        super.invokeHeadMap(headMap, context);
    }

    @Override
    public void doAfterAllAnalysed(AnalysisContext analysisContext) {
        saveData();
        insertList.clear();
        updateList.clear();
        issueCustomFieldMap.clear();
    }

    private void formatHeadMap() {
        for (Integer key : headMap.keySet()) {
            String name = headMap.get(key);
            if (headFieldTransferDic.containsKey(name)) {
                headMap.put(key, headFieldTransferDic.get(name));
            }
        }
    }

    public void validate(IssueExcelData data, StringBuilder errMsg) {
        // TODO 校验自定义字段的数据是否合法
    }

    private IssueExcelData parseDataToModel(Map<Integer, String> rowData) {
        IssueExcelData data = new IssueExcelDataFactory().getIssueExcelDataLocal();
        for (Map.Entry<Integer, String> headEntry : headMap.entrySet()) {
            Integer index = headEntry.getKey();
            String field = headEntry.getValue();
            if (StringUtils.isBlank(field)) {
                continue;
            }
            String value = StringUtils.isEmpty(rowData.get(index)) ? StringUtils.EMPTY : rowData.get(index);

            if (StringUtils.equalsIgnoreCase(field, IssueExportHeadField.ID.getName())) {
                data.setNum(StringUtils.isEmpty(value) ? null : Integer.parseInt(value));
            } else if (StringUtils.equalsAnyIgnoreCase(field, IssueExportHeadField.TITLE.getId())) {
                data.setTitle(value);
            } else if (StringUtils.equalsAnyIgnoreCase(field, IssueExportHeadField.DESCRIPTION.getId())) {
                data.setDescription(value);
            } else {
                // 自定义字段
                if (StringUtils.isNotEmpty(value) && (value.contains(","))) {
                    // 逗号分隔
                    List<String> dataList = Arrays.asList(org.springframework.util.StringUtils.trimAllWhitespace(value).split(","));
                    List<String> formatDataList = dataList.stream().map(item -> "\"" + item + "\"").collect(Collectors.toList());
                    data.getCustomData().put(field, formatDataList);
                } else if (StringUtils.isNotEmpty(value) && (value.contains(";"))){
                    // 分号分隔
                    List<String> dataList = Arrays.asList(org.springframework.util.StringUtils.trimAllWhitespace(value).split(";"));
                    List<String> formatDataList = dataList.stream().map(item -> "\"" + item + "\"").collect(Collectors.toList());
                    data.getCustomData().put(field, formatDataList);
                } else {
                    data.getCustomData().put(field, value);
                }
            }
        }
        return data;
    }

    private IssuesUpdateRequest convertToIssue(IssueExcelData issueExcelData) {
        IssuesUpdateRequest issuesUpdateRequest = new IssuesUpdateRequest();
        issuesUpdateRequest.setWorkspaceId(request.getWorkspaceId());
        issuesUpdateRequest.setProjectId(request.getProjectId());
        issuesUpdateRequest.setThirdPartPlatform(isThirdPlatform);
        issuesUpdateRequest.setDescription(issueExcelData.getDescription());
        issuesUpdateRequest.setTitle(issueExcelData.getTitle());
        if (BooleanUtils.isTrue(issueExcelData.getAddFlag())) {
            issuesUpdateRequest.setCreator(SessionUtils.getUserId());
        } else {
            issuesUpdateRequest.setPlatformId(getPlatformId(issueExcelData.getId()));
            issuesUpdateRequest.setId(issueExcelData.getId());
        }
        buildFields(issueExcelData, issuesUpdateRequest);
        return issuesUpdateRequest;
    }

    public List<ExcelErrData<IssueExcelData>> getErrList() {
        return this.errList;
    }

    /**
     * 获取注解ExcelProperty的value和对应field
     */
    public void genExcelHeadFieldTransferDic() {
        Field[] fields = dataClass.getDeclaredFields();
        for (int i = 0; i < fields.length; i++) {
            Field field = fields[i];
            ExcelProperty excelProperty = field.getAnnotation(ExcelProperty.class);
            if (excelProperty != null) {
                StringBuilder value = new StringBuilder();
                for (String v : excelProperty.value()) {
                    value.append(v);
                }
                headFieldTransferDic.put(value.toString(), field.getName());
            }
        }
    }

    private Issues checkIssueExist(Integer num, String projectId) {
        return issuesService.checkIssueExist(num, projectId);
    }

    private void buildFields(IssueExcelData issueExcelData, IssuesUpdateRequest issuesUpdateRequest) {
        if (MapUtils.isEmpty(issueExcelData.getCustomData())) {
            return;
        }
        Boolean addFlag = issueExcelData.getAddFlag();
        List<CustomFieldResourceDTO> addFields = new ArrayList<>();
        List<CustomFieldResourceDTO> editFields = new ArrayList<>();
        List<CustomFieldItemDTO> requestFields = new ArrayList<>();
        Map<String, List<CustomFieldDao>> customFieldMap = customFields.stream().collect(Collectors.groupingBy(CustomFieldDao::getName));
        issueExcelData.getCustomData().forEach((k, v) -> {
            try {
                List<CustomFieldDao> customFieldDaos = customFieldMap.get(k);
                if (CollectionUtils.isNotEmpty(customFieldDaos) && customFieldDaos.size() > 0) {
                    CustomFieldDao customFieldDao = customFieldDaos.get(0);
                    String type = customFieldDao.getType();
                    // addfield
                    CustomFieldResourceDTO customFieldResourceDTO = new CustomFieldResourceDTO();
                    customFieldResourceDTO.setFieldId(customFieldDao.getId());
                    // requestfield
                    CustomFieldItemDTO customFieldItemDTO = new CustomFieldItemDTO();
                    BeanUtils.copyBean(customFieldItemDTO, customFieldDao);
                    if (StringUtils.isEmpty(v.toString())) {
                        if (StringUtils.equalsAnyIgnoreCase(type, CustomFieldType.MULTIPLE_MEMBER.getValue(),
                                CustomFieldType.MULTIPLE_SELECT.getValue(), CustomFieldType.CHECKBOX.getValue(),
                                CustomFieldType.CASCADING_SELECT.getValue())) {
                            customFieldResourceDTO.setValue("[]");
                            customFieldItemDTO.setValue("[]");
                        } else if (StringUtils.equalsAnyIgnoreCase(type, CustomFieldType.MULTIPLE_INPUT.getValue())) {
                            customFieldResourceDTO.setValue("[]");
                            customFieldItemDTO.setValue(Collections.emptyList());
                        } else if (StringUtils.equalsAnyIgnoreCase(type, CustomFieldType.RADIO.getValue(),
                                CustomFieldType.RICH_TEXT.getValue(), CustomFieldType.SELECT.getValue(),
                                CustomFieldType.FLOAT.getValue(), CustomFieldType.DATE.getValue(),
                                CustomFieldType.DATETIME.getValue(), CustomFieldType.INPUT.getValue())) {
                            customFieldResourceDTO.setValue(StringUtils.EMPTY);
                            customFieldItemDTO.setValue(StringUtils.EMPTY);
                        } else if (StringUtils.equalsAnyIgnoreCase(type, CustomFieldType.TEXTAREA.getValue())) {
                            customFieldItemDTO.setValue(StringUtils.EMPTY);
                        }
                    } else {
                        if (StringUtils.equalsAnyIgnoreCase(type,
                                CustomFieldType.RICH_TEXT.getValue(), CustomFieldType.TEXTAREA.getValue())) {
                            customFieldResourceDTO.setTextValue(v.toString());
                        } else if (StringUtils.equalsAnyIgnoreCase(type, CustomFieldType.FLOAT.getValue())) {
                            customFieldResourceDTO.setValue(v.toString());
                        } else if (StringUtils.equalsAnyIgnoreCase(type, CustomFieldType.MULTIPLE_SELECT.getValue(),
                                CustomFieldType.CHECKBOX.getValue(), CustomFieldType.MULTIPLE_INPUT.getValue(),
                                CustomFieldType.MULTIPLE_MEMBER.getValue(), CustomFieldType.CASCADING_SELECT.getValue())) {
                            if (!v.toString().contains("[")) {
                                v = List.of("\"" + v.toString() + "\"");
                            }
                            customFieldResourceDTO.setValue(v.toString());
                        } else if (StringUtils.equalsAnyIgnoreCase(type, CustomFieldType.DATE.getValue())) {
                            Date vdate = DateUtils.parseDate(v.toString(), "yyyy/MM/dd");
                            v = DateUtils.format(vdate, "yyyy-MM-dd");
                            customFieldResourceDTO.setValue("\"" + v + "\"");
                        } else if (StringUtils.equalsAnyIgnoreCase(type, CustomFieldType.DATETIME.getValue())) {
                            Date vdate = DateUtils.parseDate(v.toString());
                            v =  DateUtils.format(vdate, "yyyy-MM-dd'T'HH:mm");
                            customFieldResourceDTO.setValue("\"" + v + "\"");
                        } else {
                            customFieldResourceDTO.setValue("\"" + v + "\"");
                        }
                    }
                    if (addFlag) {
                        addFields.add(customFieldResourceDTO);
                    } else {
                        editFields.add(customFieldResourceDTO);
                    }
                    if (StringUtils.equalsAnyIgnoreCase(type, CustomFieldType.MULTIPLE_INPUT.getValue())) {
                        customFieldItemDTO.setValue(v);
                    } else if (StringUtils.equalsAnyIgnoreCase(type, CustomFieldType.FLOAT.getValue())) {
                        customFieldItemDTO.setValue(StringUtils.isNotEmpty(v.toString()) ? Float.parseFloat(v.toString()) : StringUtils.EMPTY);
                    } else {
                        customFieldItemDTO.setValue(v.toString());
                    }
                    requestFields.add(customFieldItemDTO);
                }
            } catch (Exception e) {
                MSException.throwException(e.getMessage());
            }
        });
        if (addFlag) {
            issuesUpdateRequest.setAddFields(addFields);
        } else {
            issuesUpdateRequest.setEditFields(editFields);
        }
        issuesUpdateRequest.setRequestFields(requestFields);
    }

    private String getPlatformId(String issueId) {
        return issuesService.getIssue(issueId).getPlatformId();
    }
}