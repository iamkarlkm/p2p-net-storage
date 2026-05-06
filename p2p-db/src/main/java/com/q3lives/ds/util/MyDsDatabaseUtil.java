package com.q3lives.ds.util;

import com.alibaba.fastjson.JSON;
import com.google.common.collect.Lists;
import com.q3lives.ds.annotation.DsManyToMany;
import com.q3lives.ds.annotation.DsMapField;
import com.q3lives.ds.annotation.DsOneToMany;
import com.q3lives.ds.annotation.DsOneToOne;

import com.q3lives.ds.annotation.query.CodeType;
import com.q3lives.ds.annotation.query.JoinPropToStringArray;
import com.q3lives.ds.annotation.query.JoinProps;
import com.q3lives.ds.annotation.query.JoinPropsArray;
import com.q3lives.ds.annotation.query.OrderByProp;
import com.q3lives.ds.annotation.query.TransformStringArray;
import com.q3lives.ds.annotation.query.UnionJoinProps;
import com.q3lives.ds.annotation.query.UnionJoinPropsArray;
import com.q3lives.ds.database.integration.GenericManager;
import com.q3lives.ds.database.integration.QueryWrapper;
import com.q3lives.ds.database.interfaces.VoCallback;
import java.lang.annotation.Annotation;
import java.lang.reflect.Field;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.Arrays;
import java.util.Collection;
import java.util.HashSet;
import java.util.Set;
import lombok.extern.slf4j.Slf4j;

/**
 * sql工具类
 *
 * @author iamkarl@163.com
 * @since 2020-10-11
 */
@Slf4j
public class MyDsDatabaseUtil {

    private static final String SUFFIX_COMMA = ",";
    private static final String SUFFIX_KG = " ";
    /**
     * 模糊查询符号
     */
    private static final String SUFFIX_ASTERISK = "*";
    private static final String SUFFIX_ASTERISK_VAGUE = "%%";
    /**
     * 不等于查询符号
     */
    private static final String SUFFIX_NOT_EQUAL = "!";
    private static final String SUFFIX_NOT_EQUAL_NULL = "!NULL";
//    /**
//     * 数据字典编码通用服务接口类 自动封装数据字典--必须预初始化
//     */
//    private static DictTypeService dictTypeService;
    private static Map<Class<?>, GenericManager> SERVICE_MAP = new ConcurrentHashMap();

    //private static Map<Class<?>,List<Field>> VO_FIELDS_MAP = new ConcurrentHashMap();
    private static Map<Class<?>, Map<Class<?>, List<Field>>> VO_ANNOTAION_FIELDS_MAP = new ConcurrentHashMap();
    //private static Map<Field,QueryWrapper> VO_ANNOTAION_FIELD_QUERY_MAP = new ConcurrentHashMap();
    //private static Map<Field,GenericManager> VO_ANNOTAION_FIELD_SERVICE_MAP = new ConcurrentHashMap();

    private static Map<Class<?>, Map<String, Field>> VO_FIELDS_MAP = new ConcurrentHashMap();
    private static DsDictType dictTypeService;

   

    public static void registerService(Class<?> entityClass, GenericManager service) {
        if (entityClass == null) {
            throw new IllegalArgumentException("entityClass cannot be null");
        }
        if (service == null) {
            throw new IllegalArgumentException("service cannot be null");
        }
        SERVICE_MAP.put(entityClass, service);
    }

    public static GenericManager getService(Class<?> entityClass) {
        if (entityClass == null) {
            return null;
        }
        return SERVICE_MAP.get(entityClass);
    }

    public static void clearServices() {
        SERVICE_MAP.clear();
    }


    
    
    /**
     * 以注解的聚合多个数据库表的数据，并自动封装到页面模型对象
     *
     * @param service 基本实体类service
     * @param tableName 基本实体类表名
     * @param idName 基本实体类ID名
     * @param entityClass 基本实体类
     * @param persistentFields 基本实体类持久化字段list
     * @param field2ColMap 基本实体类字段与数据库表字段映射MAP
     * @param fieldsMap Java类反射字段对象缓存map
     * @param voClass 页面模型类
     * @param list 待转换list
     * @return
     */
    public static List<Object> transformToVo(GenericManager service, String tableName, String idName, Class entityClass, List<Field> persistentFields,
            Map<String, String> field2ColMap, Map<String, Field> fieldsMap, Class voClass, List<Object> list) {
        if(list.size()<=100){
            return transformToVoInner(service, tableName, idName, entityClass, persistentFields, field2ColMap, fieldsMap, voClass, list);
        }
        List<Object> result = new ArrayList();
        //分片处理,以便sql参数过多出错,比如in值参数等
        List<List<Object>> lists = Lists.partition(list, 100);
        for(List<Object> l :lists){
            result.addAll(transformToVoInner(service, tableName, idName, entityClass, persistentFields, field2ColMap, fieldsMap, voClass, l));
        }
        return result;
    }

    /**
     * 以注解的聚合多个数据库表的数据，并自动封装到页面模型对象
     *
     * @param service 基本实体类service
     * @param tableName 基本实体类表名
     * @param idName 基本实体类ID名
     * @param entityClass 基本实体类
     * @param persistentFields 基本实体类持久化字段list
     * @param field2ColMap 基本实体类字段与数据库表字段映射MAP
     * @param fieldsMap Java类反射字段对象缓存map
     * @param voClass 页面模型类
     * @param list 待转换list
     * @return
     */
    private static List<Object> transformToVoInner(GenericManager service, String tableName, String idName, Class entityClass, List<Field> persistentFields,
            Map<String, String> field2ColMap, Map<String, Field> fieldsMap, Class voClass, List<Object> list) {
        //TODO
        Map<Class<?>, List<Field>> voAnnotationMap = VO_ANNOTAION_FIELDS_MAP.get(voClass);
        Map<String, Field> voFieldsMap = VO_FIELDS_MAP.get(voClass);
        //List<Field> voFields = VO_FIELDS_MAP.get(voClass);
        if (voAnnotationMap == null) {
            voAnnotationMap = new HashMap();
            List<Field> voFields = MyBeanUtils.getAllFields(voClass);
            List<Field> copyFields = new ArrayList();
            List<Field> codeTypeFields = new ArrayList();
            List<Field> joinPropsFields = new ArrayList();
            List<Field> joinPropsArrayFields = new ArrayList();
            List<Field> unionJoinPropsFields = new ArrayList();
            List<Field> unionJoinPropsArrayFields = new ArrayList();
            List<Field> joinPropToStringArrayFields = new ArrayList();
            List<Field> transformStringArrayFields = new ArrayList();
            List<Field> manyToManyFields = new ArrayList();
             List<Field> mapFields = new ArrayList();
            List<Field> oneToManyFields = new ArrayList();
            List<Field> oneToOneFields = new ArrayList();
            voFieldsMap = new HashMap();
            //过滤不需要处理的字段
            Iterator<Field> it = voFields.iterator();
            while (it.hasNext()) {
                Field f = it.next();
                //如果对应entity存在字段这copy
                Field entityField = fieldsMap.get(f.getName());
                if (entityField != null) {
                    copyFields.add(f);
                } else {
                    voFieldsMap.put(f.getName(), f);
                }
                Annotation[] array = f.getAnnotations();
                if (array.length > 0) {
                    for (Annotation a : array) {
                        Class<? extends Annotation> t = a.annotationType();
                        if (t == CodeType.class) {
                            codeTypeFields.add(f);
                            CodeType codeType = f.getAnnotation(CodeType.class);
                            if (dictTypeService == null) {
                                Fail("Property [dictTypeService] of Util Class [%s] is not be setted!!!", MyDsDatabaseUtil.class);
                            }
                            Map<String, String> map = dictTypeService.findDictCodeMap(codeType.typeCode());
                            if (map == null || map.isEmpty()) {
                                Fail("The DictType [%s] is not defined!!!", codeType.typeCode());
                            }
                        } else if (t == JoinProps.class) {
                            joinPropsFields.add(f);
                            JoinProps joinProps = f.getAnnotation(JoinProps.class);
                            GenericManager joinService = SERVICE_MAP.get(joinProps.targetEntity());
                            if (joinService == null) {
                                Fail("The entityClass [%s] ORM service is not find!!!", joinProps.targetEntity());
                            }
                            //VO_ANNOTAION_FIELD_SERVICE_MAP.put(f, joinService);
                            //joinService.findOneByProps(joinProps.props(), joinProps.joinTo(), array);

                        } else if (t == JoinPropsArray.class) {
                            joinPropsArrayFields.add(f);
                            //JoinPropsArray joinPropsArray = f.getAnnotation(JoinPropsArray.class);
//                            for(JoinProps joinProps : joinPropsArray.value()){
//                                 GenericManager joinService = SERVICE_MAP.get(joinProps.joinClass());
//                            if(joinService == null){
//                                Fail("The entityClass [%s] ORM service is not find!!!",joinProps.joinClass());
//                            }joinPropsFields.add(f);
//                            }

                        }else if (t == UnionJoinProps.class) {
                            unionJoinPropsFields.add(f);
                            UnionJoinProps joinProps = f.getAnnotation(UnionJoinProps.class);
                            GenericManager joinService = SERVICE_MAP.get(joinProps.targetEntity());
                            if (joinService == null) {
                                Fail("The entityClass [%s] ORM service is not find!!!", joinProps.targetEntity());
                            }
                            //VO_ANNOTAION_FIELD_SERVICE_MAP.put(f, joinService);
                            //joinService.findOneByProps(joinProps.props(), joinProps.joinTo(), array);

                        } else if (t == UnionJoinPropsArray.class) {
                            unionJoinPropsArrayFields.add(f);
                            //JoinPropsArray joinPropsArray = f.getAnnotation(JoinPropsArray.class);
//                            for(JoinProps joinProps : joinPropsArray.value()){
//                                 GenericManager joinService = SERVICE_MAP.get(joinProps.joinClass());
//                            if(joinService == null){
//                                Fail("The entityClass [%s] ORM service is not find!!!",joinProps.joinClass());
//                            }joinPropsFields.add(f);
//                            }

                        } else if (t == JoinPropToStringArray.class) {
                            joinPropToStringArrayFields.add(f);
                            JoinPropToStringArray joinPropToStringArray = f.getAnnotation(JoinPropToStringArray.class);
                            GenericManager joinService = SERVICE_MAP.get(joinPropToStringArray.relationsEntity());
                            if (joinService == null) {
                                Fail("The entityClass [%s] ORM service is not find!!!", joinPropToStringArray.relationsEntity());
                            }
                            //VO_ANNOTAION_FIELD_SERVICE_MAP.put(f, joinService);
                        } else if (t == TransformStringArray.class) {
                            transformStringArrayFields.add(f);
                            TransformStringArray transformStringArray = f.getAnnotation(TransformStringArray.class);
                            GenericManager joinService = SERVICE_MAP.get(transformStringArray.targetEntity());
                            if (joinService == null) {
                                Fail("The entityClass [%s] ORM service is not find!!!", transformStringArray.targetEntity());
                            }
                            //VO_ANNOTAION_FIELD_SERVICE_MAP.put(f, joinService);
                        } else if (t == DsManyToMany.class) {
                            manyToManyFields.add(f);
                            DsManyToMany join = f.getAnnotation(DsManyToMany.class);
                            GenericManager joinService = SERVICE_MAP.get(join.joinClass());
                            if (joinService == null) {
                                Fail("The entityClass [%s] ORM service is not find!!!", join.joinClass());
                            }

                            GenericManager relationService = SERVICE_MAP.get(join.joinClass());
                            if (relationService == null) {
                                Fail("The entityClass [%s] ORM service is not find!!!", join.joinClass());
                            }
                            //VO_ANNOTAION_FIELD_SERVICE_MAP.put(f, joinService);
                        } else if (t == DsOneToMany.class) {

                            oneToManyFields.add(f);
                            DsOneToMany join = f.getAnnotation(DsOneToMany.class);
                            GenericManager joinService = SERVICE_MAP.get(join.joinClass());
                            if (joinService == null) {
                                Fail("The entityClass [%s] ORM service is not find!!!", join.joinClass());
                            }
                            //VO_ANNOTAION_FIELD_SERVICE_MAP.put(f, joinService);

                        } else if (t == DsOneToOne.class) {
                            oneToOneFields.add(f);
                            DsOneToOne join = f.getAnnotation(DsOneToOne.class);
                            GenericManager joinService = SERVICE_MAP.get(f.getDeclaringClass());
                            if (joinService == null) {
                                Fail("The entityClass [%s] ORM service is not find!!!", f.getDeclaringClass());
                            }
                            //VO_ANNOTAION_FIELD_SERVICE_MAP.put(f, joinService);
                        }
                    }
                }
            }
            voAnnotationMap.put(entityClass, copyFields);
            VO_FIELDS_MAP.put(voClass, voFieldsMap);
            if (!joinPropsFields.isEmpty()) {
                voAnnotationMap.put(JoinProps.class, joinPropsFields);
            }

            if (!joinPropsArrayFields.isEmpty()) {
                voAnnotationMap.put(JoinPropsArray.class, joinPropsArrayFields);
            }
            if (!unionJoinPropsFields.isEmpty()) {
                voAnnotationMap.put(UnionJoinProps.class, unionJoinPropsFields);
            }

            if (!unionJoinPropsArrayFields.isEmpty()) {
                voAnnotationMap.put(UnionJoinPropsArray.class, unionJoinPropsArrayFields);
            }

            if (!codeTypeFields.isEmpty()) {
                voAnnotationMap.put(CodeType.class, codeTypeFields);
            }
            if (!joinPropToStringArrayFields.isEmpty()) {
                voAnnotationMap.put(JoinPropToStringArray.class, joinPropToStringArrayFields);
            }
            if (!transformStringArrayFields.isEmpty()) {
                voAnnotationMap.put(TransformStringArray.class, transformStringArrayFields);
            }
            //
            if (!oneToOneFields.isEmpty()) {
                voAnnotationMap.put(DsOneToOne.class, oneToOneFields);
            }
            if (!oneToManyFields.isEmpty()) {
                voAnnotationMap.put(DsOneToMany.class, oneToManyFields);
            }
            if (!manyToManyFields.isEmpty()) {
                voAnnotationMap.put(DsManyToMany.class, manyToManyFields);
            }
            if (!mapFields.isEmpty()) {
                voAnnotationMap.put(DsMapField.class, mapFields);
            }
            VO_ANNOTAION_FIELDS_MAP.put(voClass, voAnnotationMap);
        }
        //System.out.println(list);
        List<Object> result = new ArrayList();
        try {
            //处理基本字段拷贝
            for (Object obj : list) {

                Object vo = voClass.newInstance();
                List<Field> copyFields = voAnnotationMap.get(entityClass);
                if (copyFields != null) {
                    for (Field f : copyFields) {
                        Field entityField = fieldsMap.get(f.getName());
                        Object val = entityField.get(obj);
                        //System.out.println(f.getName()+"="+val);
                        if (val != null) {
                            f.set(vo, val);
                        }
                    }
                }
                result.add(vo);
            }
            //System.out.println(result);
            //处理数据字典
            List<Field> codeTypeFields = voAnnotationMap.get(CodeType.class);
            if (codeTypeFields != null) {
                for (Field f : codeTypeFields) {
                    CodeType codeType = f.getAnnotation(CodeType.class);
                    Field voField = voFieldsMap.get(codeType.toProp());
                    if (voField == null) {
                        Fail("The vo class [%s] filed [%s] is not find!!", codeType.toProp());
                    }
                    for (Object r : result) {
                        Object code = f.get(r);
                        if (code != null) {
                            voField.set(r, dictTypeService.codeToName(codeType.typeCode(), code.toString()));
                        }
                    }
                }
            }

            //处理JoinProps
            checkJoinProps(entityClass, fieldsMap, voClass, list, voAnnotationMap, voFieldsMap, result);

            //处理JoinPropsArray
            checkJoinPropsArray(entityClass, fieldsMap, voClass, list, voAnnotationMap, voFieldsMap, result);
//            System.out.println("checkUnionJoinProps");
            //处理UnionJoinProps
            checkUnionJoinProps(entityClass, fieldsMap, voClass, list, voAnnotationMap, voFieldsMap, result);
//            System.out.println("*************checkUnionJoinProps*************");
            //处理JoinPropsArray
            checkUnionJoinPropsArray(entityClass, fieldsMap, voClass, list, voAnnotationMap, voFieldsMap, result);

            //处理JoinPropToStringArray
            checkJoinPropToStringArray(entityClass, fieldsMap, list, voAnnotationMap, result);

            //处理JoinPropToStringArray
            checkTransformStringArray(entityClass, voClass, fieldsMap, list, voAnnotationMap, voFieldsMap, result);

            //处理OneToOne
            checkOneToOne(entityClass, voClass, fieldsMap, list, voAnnotationMap, result);

            //处理OneToMany
            checkOneToMany(entityClass, voClass, fieldsMap, list, voAnnotationMap, result);

            //处理ManyToMany
            checkManyToMany(entityClass, voClass, fieldsMap, list, voAnnotationMap, result);
//            System.out.println("*************vo后处理*************");
            //vo后处理
            try {
                if (VoCallback.class.isAssignableFrom(voClass)) {
                    for (Object vo : result) {
                        ((VoCallback) vo).callback();
                    }
                }
            } catch (Exception e) {
                log.error(e.getMessage(), e);
            }
            

        } catch (Exception ex) {
            log.error(ex.getMessage(), ex);
            //throw new RuntimeException(ex);
            Fail("excuting transformToVo error->", StringUtils.exceptionStacktraceToStringByFilter(ex));
        }
        return result;
    }

    private static void checkJoinProps(Class entityClass, Map<String, Field> fieldsMap, Class voClass, List<Object> list, Map<Class<?>, List<Field>> voAnnotationMap, Map<String, Field> voFieldsMap, List<Object> result) throws IllegalArgumentException, IllegalAccessException {
        //处理JoinProps
        List<Field> joinPropsFields = voAnnotationMap.get(JoinProps.class);
        if (joinPropsFields != null) {
            for (Field f : joinPropsFields) {
                JoinProps joinProps = f.getAnnotation(JoinProps.class);
                processJoinProps(entityClass, fieldsMap, voClass, list, f, joinProps, voFieldsMap, result);
            }
        }
    }

    private static void checkJoinPropsArray(Class entityClass, Map<String, Field> fieldsMap, Class voClass, List<Object> list, Map<Class<?>, List<Field>> voAnnotationMap, Map<String, Field> voFieldsMap, List<Object> result) throws IllegalArgumentException, IllegalAccessException {
        //处理JoinProps
        List<Field> joinPropsFields = voAnnotationMap.get(JoinPropsArray.class);
        if (joinPropsFields != null) {
            for (Field f : joinPropsFields) {
                JoinPropsArray joinPropsArray = f.getAnnotation(JoinPropsArray.class);
                for (JoinProps joinProps : joinPropsArray.value()) {
                    processJoinProps(entityClass, fieldsMap, voClass, list, f, joinProps, voFieldsMap, result);
                }
            }
        }
    }

    private static void processJoinProps(Class entityClass, Map<String, Field> fieldsMap, Class voClass, List<Object> list, Field f, JoinProps joinProps,
            Map<String, Field> voFieldsMap, List<Object> resultList) throws IllegalArgumentException, IllegalAccessException {
        //处理JoinProps
        GenericManager joinService = SERVICE_MAP.get(joinProps.targetEntity());
        if (joinService == null) {
            Fail("The entityClass [%s] ORM service is not find!!!", joinProps.targetEntity());
        }
        String joinByProp = joinProps.joinBy();
        if (StringUtils.isBlank(joinByProp)) {
            joinByProp = f.getName();
        }
        Field joinField = fieldsMap.get(joinByProp);
        if (joinField == null) {
            Fail("The entityClass [%s] ORM persistent field [%s] map is not defined!!!", entityClass, joinByProp);
        }
        Set<Object> params = new HashSet();
        Map<Object, List<Object>> resultMap = new HashMap();
        int i = 0;
        for (Object obj : list) {
            try {
                Object val = joinField.get(obj);
                if (val != null) {
                    params.add(val);
                    List<Object> results = resultMap.get(val);
                    if (results == null) {
                        results = new ArrayList();
                        resultMap.put(val, results);
                    }
                    results.add(resultList.get(i));
                }
            } catch (Exception ex) {
                log.error(ex.getMessage(), ex);
                Fail("excuting transformToVo error->", StringUtils.exceptionStacktraceToStringByFilter(ex));
            }
            i++;
        }
        if(params.isEmpty()) return;
        String joinToProp = joinProps.joinTo();
        Map<String, String> joinField2ColMap = joinService.getField2ColMap();
        String joinToCol = joinField2ColMap.get(joinToProp);
        if (joinToCol == null) {
            Fail("The entityClass [%s] ORM persistent field [%s] map is not defined!!!", joinProps.targetEntity(), joinToProp);
        }
        String propsTo = joinProps.propsTo();
        if (StringUtils.isBlank(propsTo)) {
            propsTo = joinProps.props();
        }
        String[] propsToArray = propsTo.split(",");
        String[] propsArray = joinProps.props().split(",");
        if (propsToArray.length == 0 || propsToArray.length != propsToArray.length) {
            Fail("The vo class [%s] filed [%s] Anotation JoinProps is invalid -> props/propsTo is empty or props/propsTo's prop count is not same!!", voClass, f.getName());
        }
        String selectProps = joinProps.props();
        if (!selectProps.contains(joinToProp)) {//如果定义的查询返回值不包含外键本身
            selectProps = selectProps + "," + joinToProp;
        }
        List<Object> joinToEntities = joinService.findListByProps(selectProps, joinToProp, params);
        Map<String, Field> joinToFieldsMap = joinService.getFieldsMap();
        Field joinToField = joinToFieldsMap.get(joinToProp);

        for (Object joinToEntity : joinToEntities) {
            Object val = joinToField.get(joinToEntity);
            if (val != null) {
                List<Object> results = resultMap.get(val);
                if (results != null) {//对应vo存在,进行设值操作
                    for (Object vo : results) {
                        for (int j = 0; j < propsToArray.length; j++) {
                            Field voField = voFieldsMap.get(propsToArray[j]);
                            if (voField == null) {
                                Fail("The vo class [%s] filed [%s] is not find!!", voClass, propsToArray[j]);
                            }
                            Field joinToField_j = joinToFieldsMap.get(propsArray[j]);
                            Object joinVal = joinToField_j.get(joinToEntity);
                            if (joinVal != null) {
                                voField.set(vo, joinVal);
                            }
                        }
                    }
                }
            }
        }
    }

    private static void checkUnionJoinProps(Class entityClass, Map<String, Field> fieldsMap, Class voClass, List<Object> list, Map<Class<?>, List<Field>> voAnnotationMap, Map<String, Field> voFieldsMap, List<Object> result) throws IllegalArgumentException, IllegalAccessException {
        //处理UnionJoinProps
        List<Field> joinPropsFields = voAnnotationMap.get(UnionJoinProps.class);
        if (joinPropsFields != null) {
            for (Field f : joinPropsFields) {
                UnionJoinProps joinProps = f.getAnnotation(UnionJoinProps.class);
                processUnionJoinProps(entityClass, fieldsMap, voClass, list, f, joinProps, voFieldsMap, result);
            }
        }
    }

    private static void checkUnionJoinPropsArray(Class entityClass, Map<String, Field> fieldsMap, Class voClass, List<Object> list, Map<Class<?>, List<Field>> voAnnotationMap, Map<String, Field> voFieldsMap, List<Object> result) throws IllegalArgumentException, IllegalAccessException {
        //处理UnionJoinPropsArray
        List<Field> joinPropsFields = voAnnotationMap.get(UnionJoinPropsArray.class);
        if (joinPropsFields != null) {
            for (Field f : joinPropsFields) {
                UnionJoinPropsArray joinPropsArray = f.getAnnotation(UnionJoinPropsArray.class);
                for (UnionJoinProps joinProps : joinPropsArray.value()) {
                    processUnionJoinProps(entityClass, fieldsMap, voClass, list, f, joinProps, voFieldsMap, result);
                }
            }
        }
    }

    private static void processUnionJoinProps_bak(Class entityClass, Map<String, Field> fieldsMap, Class voClass, List<Object> list, Field f, UnionJoinProps joinProps,
            Map<String, Field> voFieldsMap, List<Object> resultList) throws IllegalArgumentException, IllegalAccessException {
        //处理JoinProps
        GenericManager joinService = SERVICE_MAP.get(joinProps.targetEntity());
        if (joinService == null) {
            Fail("The entityClass [%s] ORM service is not find!!!", joinProps.targetEntity());
        }
        String joinByProp = joinProps.joinBy();
        if (StringUtils.isBlank(joinByProp)) {
            Fail("The entityClass [%s] ORM persistent field [%s] -> anotation [UnionJoinProps] property [joinBy] can not empty!", voClass, f.getName());
        }
        String[] joinByProps = joinByProp.split(",");
        if (joinByProps.length < 2) {
            Fail("The entityClass [%s] ORM persistent field [%s] -> anotation [UnionJoinProps] property [joinBy] is invalid!", voClass, f.getName());
        }
        
        String joinByFixedValues = joinProps.joinByFixedValues();
        Map<String,Object> joinByFixedPropsMap = null;
        if (!StringUtils.isBlank(joinByFixedValues)) {
            joinByFixedPropsMap = JSON.parseObject(joinByFixedValues, HashMap.class);
        }
        
        Field[] joinByFields = new Field[joinByProps.length];
        for (int i = 0; i < joinByProps.length; i++) {
            Field joinByField = fieldsMap.get(joinByProps[i]);
            if (joinByFixedPropsMap == null) {
                joinByFields[i] = joinByField;
            } else if(joinByFixedPropsMap.get(joinByProps[i])!=null){
                joinByFields[i] = null;
            }else{
                Fail("The entityClass [%s] ORM persistent field [%s] map is not defined!!!", entityClass, joinByProps[i]);
            }
        }

        String joinToProp = joinProps.joinTo();
        if (StringUtils.isBlank(joinByProp)) {
            Fail("The entityClass [%s] ORM persistent field [%s] -> anotation [UnionJoinProps] property [joinTo] can not empty!", voClass, f.getName());
        }
        String[] joinToProps = joinToProp.split(",");
        if (joinToProps.length < 2) {
            Fail("The entityClass [%s] ORM persistent field [%s] -> anotation [UnionJoinProps] property [joinTo] is invalid!", voClass, f.getName());
        }

        Map<String, String> joinField2ColMap = joinService.getField2ColMap();
        //String[] joinToCols = new String[joinToProps.length];
        for (int i = 0; i < joinToProps.length; i++) {
            String joinToCol = joinField2ColMap.get(joinToProps[i]);
            if (joinToCol == null) {
                Fail("The entityClass [%s] ORM persistent field [%s] map is not defined!!!", joinProps.targetEntity(), joinToProp);
            }
            //joinToCols[i] = joinToCol;
        }

        String propsTo = joinProps.propsTo();
        if (StringUtils.isBlank(propsTo)) {
            propsTo = joinProps.props();
        }
        String[] propsToArray = propsTo.split(",");
        String[] propsArray = joinProps.props().split(",");
        if (propsToArray.length == 0 || propsToArray.length != propsToArray.length) {
            Fail("The vo class [%s] filed [%s] Anotation JoinProps is invalid -> props/propsTo is empty or props/propsTo's prop count is not same!!", voClass, f.getName());
        }
        String selectProps = joinProps.props();
        Map<String, Field> joinToFieldsMap = joinService.getFieldsMap();
        
        String orders = joinProps.orders();
        if (StringUtils.isBlank(orders)) {
            orders = null;
        }
        int i = 0;
        for (Object obj : list) {
            try {
                boolean needJoin = true;
                Object[] joinByValues = new Object[joinToProps.length];
                for (int j = 0; j < joinByFields.length; j++) {
                    Field joinByField = joinByFields[j];
                    if (joinByField != null) {
                        Object val = joinByField.get(obj);
                        if (val != null) {
                            joinByValues[j] = val;
                        } else {//当存在空值,跳过join
                            //joinByValues[j] = null;
                            needJoin = false;
                        }
                    }else{
                        Object val = joinByFixedPropsMap.get(joinByProps[i]);
                        if(val!=null){
                            joinByValues[j] = val;
                        }else{
                            needJoin = false;
                        }
                    }
                }
                if(!needJoin){
                    continue;
                }
                Object joinedEntity = joinService.findOneByProps(orders,selectProps, joinToProp, joinByValues);
                if (joinedEntity != null) {
                    Object vo = resultList.get(i);
                    for (String prop : propsArray) {
                        Field joinToField = joinToFieldsMap.get(prop);
                        Object joinToVal = joinToField.get(joinedEntity);
                        if (joinToVal != null) {
                            for (int j = 0; j < propsToArray.length; j++) {
                                Field voField = voFieldsMap.get(propsToArray[j]);
                                if (voField == null) {
                                    Fail("The vo class [%s] filed [%s] is not find!", voClass, propsToArray[j]);
                                }
                                voField.set(vo, joinToVal);
                            }
                        }
                    }
                }
                
            } catch (Exception ex) {
                log.error(ex.getMessage(), ex);
                Fail("excuting transformToVo error->", StringUtils.exceptionStacktraceToStringByFilter(ex));
            }
            i++;
        }

    }
    
    private static void processUnionJoinProps(Class entityClass, Map<String, Field> fieldsMap, Class voClass, List<Object> list, Field f, UnionJoinProps joinProps,
            Map<String, Field> voFieldsMap, List<Object> resultList) throws IllegalArgumentException, IllegalAccessException {
        //处理JoinProps
        GenericManager joinService = SERVICE_MAP.get(joinProps.targetEntity());
        if (joinService == null) {
            Fail("The entityClass [%s] ORM service is not find!!!", joinProps.targetEntity());
        }
        String joinByProp = joinProps.joinBy();
        if (StringUtils.isBlank(joinByProp)) {
            Fail("The entityClass [%s] ORM persistent field [%s] -> anotation [UnionJoinProps] property [joinBy] can not empty!", voClass, f.getName());
        }
        String[] joinByProps = joinByProp.split(",");
        if (joinByProps.length < 2) {
            Fail("The entityClass [%s] ORM persistent field [%s] -> anotation [UnionJoinProps] property [joinBy] is invalid!", voClass, f.getName());
        }
        
        String joinByFixedValues = joinProps.joinByFixedValues();
        Map<String,Object> joinByFixedPropsMap = null;
        if (!StringUtils.isBlank(joinByFixedValues)) {
            joinByFixedPropsMap = JSON.parseObject(joinByFixedValues, HashMap.class);
        }
        
        Field[] joinByFields = new Field[joinByProps.length];
        Integer mainIndex = null;
        for (int i = 0; i < joinByProps.length; i++) {
            Field joinByField = fieldsMap.get(joinByProps[i]);
            if (joinByFixedPropsMap == null ||joinByFixedPropsMap.get(joinByProps[i])==null) {
                joinByFields[i] = joinByField;
                if(mainIndex == null){//使用第一个字段作为主连接字段
                   mainIndex = i;
                }
            } else if(joinByFixedPropsMap.get(joinByProps[i])!=null){
                joinByFields[i] = null;
            }else{
                Fail("The entityClass [%s] ORM persistent field [%s] map is not defined!!!", entityClass, joinByProps[i]);
            }
        }

        String joinToProp = joinProps.joinTo();
        if (StringUtils.isBlank(joinByProp)) {
            Fail("The entityClass [%s] ORM persistent field [%s] -> anotation [UnionJoinProps] property [joinTo] can not empty!", voClass, f.getName());
        }
        String[] joinToProps = joinToProp.split(",");
        if (joinToProps.length < 2) {
            Fail("The entityClass [%s] ORM persistent field [%s] -> anotation [UnionJoinProps] property [joinTo] is invalid!", voClass, f.getName());
        }

        Map<String, String> joinField2ColMap = joinService.getField2ColMap();
        //String[] joinToCols = new String[joinToProps.length];
        for (int i = 0; i < joinToProps.length; i++) {
            String joinToCol = joinField2ColMap.get(joinToProps[i]);
            if (joinToCol == null) {
                Fail("The entityClass [%s] ORM persistent field [%s] map is not defined!!!", joinProps.targetEntity(), joinToProp);
            }
            //joinToCols[i] = joinToCol;
        }

        String propsTo = joinProps.propsTo();
        if (StringUtils.isBlank(propsTo)) {
            propsTo = joinProps.props();
        }
        String[] propsToArray = propsTo.split(",");
        String[] propsArray = joinProps.props().split(",");
        if (propsToArray.length == 0 || propsToArray.length != propsToArray.length) {
            Fail("The vo class [%s] filed [%s] Anotation JoinProps is invalid -> props/propsTo is empty or props/propsTo's prop count is not same!!", voClass, f.getName());
        }
        String selectProps = joinProps.props();
        
        String orders = joinProps.orders();
        if (StringUtils.isBlank(orders)) {
            orders = null;
        }
        
        Set<Object> params = new HashSet();
        Map<String,Object> paramsMap = new HashMap();
        Map<Object, List<Object>> resultMap = new HashMap();
        Map<Object, List<Object>> entitiesMap = new HashMap();
        int i = 0;
        for (Object obj : list) {
            try {
                Object val = joinByFields[mainIndex].get(obj);
                if (val != null) {
                    boolean needJoin = true;
                    for (int k = 0; k < joinByProps.length; k++) {
                        if (joinByFixedPropsMap == null || joinByFixedPropsMap.get(joinByProps[k]) == null) {
                            if (mainIndex == k) {//已经作为查询条件跳过
                                continue;
                            }
                            Object joinByVal = joinByFields[k].get(obj);
                            if(joinByVal==null){//值为空,不做连接处理
                                needJoin = false;break;
                            }
                        } else if (joinByFixedPropsMap.get(joinByProps[k]) != null) {//已经作为查询条件跳过
                            continue;
                        } 
                    }
                    if(!needJoin){
                        continue;
                    }
                    params.add(val);
                    List<Object> results = resultMap.get(val);
                    List<Object> entities = entitiesMap.get(val);
                    if (results == null) {
                        results = new ArrayList();
                        entities = new ArrayList();
                        resultMap.put(val, results);
                        entitiesMap.put(val, entities);
                    }
                    entities.add(obj);
                    results.add(resultList.get(i));
                    if (joinByFixedPropsMap != null) {
                        for (int j = 0; j < joinByProps.length; j++) {//特殊处理固定值
                            Object fixedVal = joinByFixedPropsMap.get(joinByProps[j]);
                            if (fixedVal != null) {
                                paramsMap.put(joinToProps[j], fixedVal);
                            }
                        }
                    }
                }
            } catch (Exception ex) {
                log.error(ex.getMessage(), ex);
                Fail("excuting transformToVo error->", StringUtils.exceptionStacktraceToStringByFilter(ex));
            }
            i++;
        }
        
        if(!selectProps.contains(joinToProps[mainIndex])){
            selectProps = selectProps + ',' + joinToProps[mainIndex];
        }
        List<Object> joinToEntities = null;
        if(paramsMap.isEmpty()){//无固定值连接查询
            if(params.isEmpty()) return;
            joinToEntities = joinService.findListByPropsWithOrders(orders,selectProps, joinToProps[mainIndex], params);
        }else {
            if(!params.isEmpty()){
               paramsMap.put(joinToProps[mainIndex]+"_In", params); 
            }
            joinToEntities = joinService.findPropsRangeByMapWithOrders(0,0,selectProps, paramsMap,orders);
        }
        
        Map<String, Field> joinToFieldsMap = joinService.getFieldsMap();
        Field joinToField = joinToFieldsMap.get(joinToProps[mainIndex]);
        if(joinToField == null){
            Fail("The entityClass [%s] ORM persistent field [%s] map is not defined!", joinProps.targetEntity(), joinToProps[mainIndex]);
        }
        if(joinToEntities == null || joinToEntities.isEmpty()) return;
        for (Object joinToEntity : joinToEntities) {
            if (joinToEntity == null) {
                Fail("[%s] -> joinToEntities can not be null:\n%s", joinToProps[mainIndex], joinToEntities);
            }
            Object val = joinToField.get(joinToEntity);
            if(val == null){
                continue;
            }
            List<Object> results = resultMap.get(val);
            if (results != null) {//对应vo存在,进行设值操作
                List<Object> entities = entitiesMap.get(val);
                i = 0;
                for (Object entity : entities) {
                    boolean needSet = true;
                    for (int k = 0; k < joinByProps.length; k++) {
                        if (joinByFixedPropsMap == null || joinByFixedPropsMap.get(joinByProps[k]) == null) {
                            if (mainIndex == k) {//已经作为查询条件跳过
                                continue;
                            }
                            Object joinByVal = joinByFields[k].get(entity);
                            Field joinToField_k = joinToFieldsMap.get(joinToProps[k]);
                            Object joinToVal = joinToField_k.get(joinToEntity);
                            if(!joinByVal.equals(joinToVal)){//其他字段值不相等,不做连接处理
                                needSet = false;break;
                            }
                        } 
                    }
                    if (needSet) {
                        Object vo = results.get(i);
                        for (int j = 0; j < propsToArray.length; j++) {
                            Field voField = voFieldsMap.get(propsToArray[j]);
                            if (voField == null) {
                                Fail("The vo class [%s] filed [%s] is not find!!", voClass, propsToArray[j]);
                            }
                            if (voField.get(vo) != null) {//对应多个值对象,只设值一次,取首个.此种情形下,排序配置应不为空
                                continue;
                            }
                            Field joinToField_j = joinToFieldsMap.get(propsArray[j]);
                            Object joinVal = joinToField_j.get(joinToEntity);
                            if (joinVal != null) {
                                voField.set(vo, joinVal);
                            }
                        }
                    }
                    i++;
                }
            }
        }

    }

    private static void checkOneToOne(Class entityClass, Class voClass, Map<String, Field> fieldsMap, List<Object> list, Map<Class<?>, List<Field>> voAnnotationMap, List<Object> result) throws IllegalArgumentException, IllegalAccessException {
        //处理OneToOne
        List<Field> joinPropsFields = voAnnotationMap.get(DsOneToOne.class);
        if (joinPropsFields != null) {
            for (Field f : joinPropsFields) {
                DsOneToOne joined = f.getAnnotation(DsOneToOne.class);
                GenericManager joinService = SERVICE_MAP.get(f.getDeclaringClass());
                String joinByProp = joined.joinProp();

                Field joinField = fieldsMap.get(joinByProp);
                if (joinField == null) {
                    Fail("The entityClass [%s] ORM persistent field [%s] map is not defined!!!", entityClass, joinByProp);
                }
                Set<Object> params = new HashSet();
                Map<Object, List<Object>> resultMap = new HashMap();
                int i = 0;
                for (Object obj : list) {
                    try {
                        Object val = joinField.get(obj);
                        if (val != null) {
                            params.add(val);
                            List<Object> results = resultMap.get(val);
                            if (results == null) {
                                results = new ArrayList();
                                resultMap.put(val, results);
                            }
                            results.add(result.get(i));
                        }
                    } catch (Exception ex) {
                        log.error(ex.getMessage(), ex);
                        Fail("excuting transformToVo error->", StringUtils.exceptionStacktraceToStringByFilter(ex));
                    }
                    i++;
                }
                if(params.isEmpty()) continue;
                String joinToProp = joined.joinProp();
                Map<String, String> joinField2ColMap = joinService.getField2ColMap();
                String joinToCol = joinField2ColMap.get(joinToProp);
                if (joinToCol == null) {
                    Fail("The entityClass [%s] ORM persistent field [%s] map is not defined!!!", f, joinToProp);
                }
                String props = joined.props();

                String[] propsArray = props.split(",");
                if (propsArray.length == 0) {
                    Fail("The vo class [%s] filed [%s] Anotation OneToOne is invalid -> props is empty!!", voClass, f.getName());
                }
                String selectProps = joined.props();
                if (!selectProps.contains(joinToProp)) {
                    selectProps = selectProps + "," + joinToProp;
                }
                List<Object> joins = joinService.findListByProps(selectProps, joinToProp, params);
                Map<String, Field> joinToFieldsMap = joinService.getFieldsMap();
                Field joinToField = joinToFieldsMap.get(joinToProp);

                for (Object join : joins) {
                    Object val = joinToField.get(join);
                    if (val != null) {
                        List<Object> results = resultMap.get(val);
                        if (results != null) {//对应vo存在,进行设值操作
                            for (Object r : results) {
                                f.set(r, join);
                            }
                        }
                    }
                }
            }
        }
    }

    private static void checkOneToMany(Class entityClass, Class voClass, Map<String, Field> fieldsMap, List<Object> list, Map<Class<?>, List<Field>> voAnnotationMap, List<Object> result) throws IllegalArgumentException, IllegalAccessException {
        //处理OneToOne
        List<Field> joinPropsFields = voAnnotationMap.get(DsOneToMany.class);
        if (joinPropsFields != null) {
            for (Field f : joinPropsFields) {
                DsOneToMany joined = f.getAnnotation(DsOneToMany.class);
                GenericManager joinService = SERVICE_MAP.get(joined.joinClass());
                String joinByProp = joined.joinProp();

                Field joinField = fieldsMap.get(joinByProp);
                if (joinField == null) {
                    Fail("The entityClass [%s] ORM persistent field [%s] map is not defined!!!", entityClass, joinByProp);
                }
                String joinToProp = joined.joinProp();
                Map<String, String> joinField2ColMap = joinService.getField2ColMap();
                String joinToCol = joinField2ColMap.get(joinToProp);
                if (joinToCol == null) {
                    Fail("The entityClass [%s] ORM persistent field [%s] map is not defined!!!", joined.joinClass(), joinToProp);
                }
                String props = joined.props();

                String[] propsArray = props.split(",");
                if (propsArray.length == 0) {
                    Fail("The vo class [%s] filed [%s] Anotation OneToOne is invalid -> props is empty!!", voClass, f.getName());
                }
                String selectProps = joined.props();
                if (!selectProps.contains(joinToProp)) {
                    selectProps = selectProps + "," + joinToProp;
                }
                //处理排序
                OrderByProp order = f.getAnnotation(OrderByProp.class);//单字段排序
                OrderWrapper ow = null;
                if (order != null) {
                    ow = OrderWrapper.build();
                    if (order.asc()) {
                        ow.orderAsc(order.value());
                    } else {
                        ow.orderDesc(order.value());
                    }
                } 
                Map<Object,List<Object>> joinsMap = new HashMap();
                int i = 0;
                for (Object obj : list) {
                    try {
                        Object val = joinField.get(obj);
                        if (val != null) {
                            List<Object> joins = joinsMap.get(val);
                            if(joins == null){
                                joins = joinService.findListByProps(ow, selectProps, joinToProp, val);
                                joinsMap.put(val,joins);
                            }
                            f.set(result.get(i), joins);
                        }
                    } catch (Exception ex) {
                        log.error(ex.getMessage(), ex);
                        Fail("excuting transformToVo error->", StringUtils.exceptionStacktraceToStringByFilter(ex));
                    }
                    i++;
                }

            }
        }
    }

    private static void checkJoinPropToStringArray(Class entityClass, Map<String, Field> fieldsMap, List<Object> list, Map<Class<?>, List<Field>> voAnnotationMap, List<Object> result) {
        //处理JoinPropToStringArray
        List<Field> joinPropToStringArrayFields = voAnnotationMap.get(JoinPropToStringArray.class);
        if (joinPropToStringArrayFields != null) {
            for (Field f : joinPropToStringArrayFields) {
                JoinPropToStringArray joinPropToStringArray = f.getAnnotation(JoinPropToStringArray.class);
                GenericManager joinService = SERVICE_MAP.get(joinPropToStringArray.relationsEntity());
                String mappedByProp = joinPropToStringArray.mappedBy();
                String joinByProp = joinPropToStringArray.joinBy();
                String joinToProp = joinPropToStringArray.joinTo();
                //String mappedToProp = joinPropToStringArray.mappedTo();
                Field mappedByField = fieldsMap.get(mappedByProp);
                if (mappedByField == null) {
                    Fail("The entityClass [%s] ORM persistent field [%s] map is not defined!!!", entityClass, mappedByProp);
                }
                Map<String, Field> joinFieldsMap = joinService.getFieldsMap();
                Field joinByField = joinFieldsMap.get(joinByProp);
                if (joinByField == null) {
                    Fail("The entityClass [%s] ORM persistent field [%s] map is not defined!!!", joinPropToStringArray.relationsEntity(), joinByProp);
                }
                Map<Object,List<Object>> joinsMap = new HashMap();
                int i = 0;
                for (Object obj : list) {
                    try {
                        Object val = mappedByField.get(obj);
                        if (val != null) {
                            List<Object> values = joinsMap.get(val);
                            if(values == null){
                                values = joinService.findPropListByProps(joinToProp, joinByProp, val);
                                joinsMap.put(val,values);
                            }
                            if (!values.isEmpty()) {
                                StringBuilder sb = new StringBuilder();
                                for (Object v : values) {
                                    sb.append(v).append(",");
                                }
                                sb.deleteCharAt(sb.length() - 1);
                                Object vo = result.get(i);
                                f.set(vo, sb.toString());
                            }
                        }
                    } catch (Exception ex) {
                        log.error(ex.getMessage(), ex);
                        Fail("excuting transformToVo error->", StringUtils.exceptionStacktraceToStringByFilter(ex));
                    }
                    i++;
                }

            }
        }
    }

    private static void checkTransformStringArray(Class entityClass, Class voClass, Map<String, Field> fieldsMap, List<Object> list, Map<Class<?>, List<Field>> voAnnotationMap, Map<String, Field> voFieldsMap, List<Object> result) {
        //处理JoinPropToStringArray
        List<Field> joinPropToStringArrayFields = voAnnotationMap.get(TransformStringArray.class);
        if (joinPropToStringArrayFields != null) {
            for (Field f : joinPropToStringArrayFields) {
                TransformStringArray joinPropToStringArray = f.getAnnotation(TransformStringArray.class);
                GenericManager joinService = SERVICE_MAP.get(joinPropToStringArray.targetEntity());
                String voProp = joinPropToStringArray.voProp();
                String fromProp = joinPropToStringArray.fromProp();
                String toProp = joinPropToStringArray.toProp();
                //String mappedToProp = joinPropToStringArray.mappedTo();
                Field voPropField = voFieldsMap.get(voProp);
                if (voPropField == null) {
                    Fail("The VO Class [%s] field [%s] is not find!!!", voClass, voProp);
                }
                Map<String, Field> joinFieldsMap = joinService.getFieldsMap();
                Field joinByField = joinFieldsMap.get(fromProp);
                if (joinByField == null) {
                    Fail("The entityClass [%s] ORM persistent field [%s] map is not defined!!!", joinPropToStringArray.targetEntity(), fromProp);
                }
                Field toPropField = joinFieldsMap.get(toProp);
                if (toPropField == null) {
                    Fail("The entityClass [%s] ORM persistent field [%s] map is not defined!!!", joinPropToStringArray.targetEntity(), toProp);
                }
                Map<Object,List<Object>> joinsMap = new HashMap();
//            int i = 0;
                for (Object obj : result) {
                    try {
                        Object val = voPropField.get(obj);
                        if (val != null) {
                            List<Object> values = joinsMap.get(val);
                            if(values == null){
                                values = joinService.findPropListByProps(toProp, fromProp, Arrays.asList(val.toString().split(",")));
                                joinsMap.put(val,values);
                            }
                            if (!values.isEmpty()) {
                                StringBuilder sb = new StringBuilder();
                                for (Object v : values) {
                                    sb.append(v).append(",");
                                }
                                sb.deleteCharAt(sb.length() - 1);
                                f.set(obj, sb.toString());
                            }
                        }
                    } catch (Exception ex) {
                        log.error(ex.getMessage(), ex);
                        Fail("excuting transformToVo error->", StringUtils.exceptionStacktraceToStringByFilter(ex));
                    }
//                i++;
                }

            }
        }
    }

    private static void checkManyToMany(Class entityClass, Class voClass, Map<String, Field> fieldsMap, List<Object> list, Map<Class<?>, List<Field>> voAnnotationMap, List<Object> result) {
        //处理JoinPropToStringArray
        List<Field> joinedFields = voAnnotationMap.get(DsManyToMany.class);
        if (joinedFields != null) {
            for (Field f : joinedFields) {
                DsManyToMany joined = f.getAnnotation(DsManyToMany.class);
                GenericManager joinService = SERVICE_MAP.get(joined.joinClass());
                GenericManager relationService = SERVICE_MAP.get(joined.joinClass());
                String mappedByProp = "id";
                String joinByProp = "id";
                String joinToProp = "id";
                //String mappedToProp = joinPropToStringArray.mappedTo();
                Field mappedByField = fieldsMap.get(mappedByProp);
                if (mappedByField == null) {
                    Fail("The entityClass [%s] ORM persistent field [%s] map is not defined!!!", entityClass, mappedByProp);
                }
                Map<String, Field> relationFieldsMap = relationService.getFieldsMap();
                Field joinByField = relationFieldsMap.get(joinByProp);
                if (joinByField == null) {
                    Fail("The entityClass [%s] ORM persistent field [%s] map is not defined!!!", joined.joinClass(), joinByProp);
                }
                //处理排序
                OrderByProp order = f.getAnnotation(OrderByProp.class);//单字段排序
                OrderWrapper ow = null;
                if (order != null) {
                    ow = OrderWrapper.build();
                    if (order.asc()) {
                        ow.orderAsc(order.value());
                    } else {
                        ow.orderDesc(order.value());
                    }
                }
                Map<Object,List<Object>> joinsMap = new HashMap();
                int i = 0;
                for (Object obj : list) {
                    try {
                        Object val = mappedByField.get(obj);
                        if (val != null) {
                            List<Object> values = joinsMap.get(val);
                            if (values == null) {
                                //                        List<Object> relationIds = relationService.findPropListByProps(joinToProp, joinByProp, val);
//                        List<Object> values = joinService.findListByProps(ow,joined.props(), mappedByProp, relationIds);
//                        System.out.println("values:"+values);
                                QueryWrapper wrapper = joinService.buildQueryWrapper(ow, joined.props(), "");
                                QueryWrapper subWrapper = wrapper.getSubWrapper(joined.joinClass());
                                Map<String, String> relationField2ColMap = relationService.getField2ColMap();
                                subWrapper.select(relationField2ColMap.get(joinToProp));
                                subWrapper.eq(relationField2ColMap.get(joinByProp), val);
                                wrapper.inSubWrapper(relationField2ColMap.get(mappedByProp), subWrapper);
//                        System.out.println("wrapper:"+wrapper.getTargetSql());
                                values = joinService.list(wrapper);
//                        System.out.println("values2:"+values2);
                                joinsMap.put(val, values);
                            }

                            if (!values.isEmpty()) {
                                Object vo = result.get(i);
                                f.set(vo, values);
                            }
                        }
                    } catch (Exception ex) {
                        log.error(ex.getMessage(), ex);
                        Fail("excuting transformToVo error->", StringUtils.exceptionStacktraceToStringByFilter(ex));
                    }
                    i++;
                }

            }
        }
    }

    protected static void Fail(String format, Object... msgs) {
        String msg = String.format(format, msgs);
        log.error(msg);
        throw new RuntimeException(msg);
    }

    protected static void CheckNull(String paraName, Object val) {
        if (null == val) {
            throw new RuntimeException(String.format("参数%s不能为空!", paraName));
        }
    }

    public static <T> T convert(Object obj,Class<T> t){
        if (obj == null) {
            return null;
        }
        if (t == String.class) {
            return (T) obj.toString();
        } else if (t == Integer.TYPE || t == Integer.class) {
            return (T) Integer.valueOf(obj.toString());
        } else if (t == Date.class) {
            return (T) DateUtil.convert(obj);
        } else if (t == Long.TYPE || t == Long.class) {
            return (T) Long.valueOf(obj.toString());
        } else if (t == Short.TYPE || t == Short.class) {
            return (T) Short.valueOf(obj.toString());
        } else if (t == Float.TYPE || t == Float.class) {
            return (T) Float.valueOf(obj.toString());
        } else if (t == Double.TYPE || t == Double.class) {
            return (T) Double.valueOf(obj.toString());
        }else if (t == LocalDate.class) {
            return (T) DateUtil.toLocalDate(DateUtil.convert(obj));
        }else if (t == LocalDateTime.class) {
            return (T) DateUtil.toLocalDateTime(DateUtil.convert(obj));
        } else {
            throw new UnsupportedOperationException("not support class type:" + t);
        }
    }

    public static Collection toLongList(String string) {
        if (string == null || string.isBlank()) {
            return new ArrayList();
        }
        String[] parts = string.split(",");
        List<Long> out = new ArrayList(parts.length);
        for (String p : parts) {
            String s = p.trim();
            if (s.isEmpty()) {
                continue;
            }
            out.add(Long.valueOf(s));
        }
        return out;
    }

   
  

}
