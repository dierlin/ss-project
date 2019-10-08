package com.rainbow.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.google.common.base.Preconditions;
import com.google.common.collect.ArrayListMultimap;
import com.google.common.collect.Lists;
import com.google.common.collect.Multimap;
import com.rainbow.common.RequestHolder;
import com.rainbow.dao.mapper.*;
import com.rainbow.domain.*;
import com.rainbow.enums.ReturnCode;
import com.rainbow.exception.BusinessException;
import com.rainbow.service.BaseService;
import com.rainbow.service.ISysRoleService;
import com.rainbow.vo.SysRoleReq;
import org.apache.commons.collections.CollectionUtils;
import org.apache.commons.lang.StringUtils;
import org.springframework.beans.BeanUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;


import java.util.*;
import java.util.stream.Collectors;

import static com.rainbow.common.Constant.ROOT;
import static com.rainbow.common.Constant.SEPARATOR;

@Service
public class SysRoleService extends BaseService<SysRoleMapper, SysRole> implements ISysRoleService {

    @Autowired
    private SysRoleUserMapper sysRoleUserMapper;

    @Autowired
    private SysRoleAclMapper sysRoleAclMapper;

    @Autowired
    private SysAclMapper sysAclMapper;

    @Autowired
    private SysAclModuleMapper sysAclModuleMapper;


    public void save(SysRoleReq param) {
        // 校验角色是否存在
        SysRole sysRole = new SysRole();
        BeanUtils.copyProperties(param, sysRole);
        sysRole.setOperateIp("127.0.0.1");
        sysRole.setOperator("system");
        sysRole.setOperateTime(new Date());
        // todo: 操作日志 > 可以使用aop实现
        if (!save(sysRole)) throw new BusinessException(ReturnCode.INTERNAL_SERVER_ERROR);
    }

    public void update(SysRoleReq param) {

        SysRole role = new SysRole();
        BeanUtils.copyProperties(param,
                Preconditions.checkNotNull(getById(param.getId()), "待更新的角色不存在"));
        role.setOperateIp("127.0.0.1");
        role.setOperator("system");
        role.setOperateTime(new Date());
        if (!updateById(role)) throw new BusinessException("更新失败");
    }

    public List<SysRole> listAll() {
        return list();
    }

    public List<SysRole> getRoleListByUserId(int userId) {

        List<Integer> roleIdList = sysRoleUserMapper.selectList(new QueryWrapper<SysRoleUser>()
                .lambda()
                .select(SysRoleUser::getId)
                .eq(SysRoleUser::getRoleId, userId))
                .stream()
                .map(s -> s.getId())
                .collect(Collectors.toList());
        if (CollectionUtils.isEmpty(roleIdList)) {
            return Lists.newArrayList();
        }
        return baseMapper.selectBatchIds(roleIdList);
    }

    public List<SysAclModuleExt> getAcl(int roleId) {
        // todo  1. 获取用户已分配的权限点   意义??
        List<SysAcl> userAclList = getCurrentUserAclList();

        // 2. 当前角色分配的权限点
        List<SysAcl> roleAclList = getRoleAclList(roleId);

        // 3. 当前系统的权限点.
        List<SysAclExt> aclExtList = Lists.newArrayList();

        sysAclMapper.selectList(null).forEach(it -> {
            SysAclExt sysAclExt = new SysAclExt();
            BeanUtils.copyProperties(it, sysAclExt);
            if (userAclList.contains(it.getId())) {
                sysAclExt.setHasAcl(true);
            }
            if (roleAclList.contains(it.getId())) {
                sysAclExt.setChecked(true);
            }
            aclExtList.add(sysAclExt);
        });

        if (CollectionUtils.isEmpty(aclExtList)) {
            return Lists.newArrayList();
        }

        // 权限模块树...
        aclModuleTree();

        return null;
    }

    private List<SysAclModuleExt> aclModuleTree() {

        List<SysAclModuleExt> aclModuleExts = sysAclModuleMapper.selectList(null).stream().map(it -> {
            SysAclModuleExt target = new SysAclModuleExt();
            BeanUtils.copyProperties(it, target);
            return target;
        }).collect(Collectors.toList());

        if (CollectionUtils.isEmpty(aclModuleExts)) {
            return Lists.newArrayList();
        }

        Multimap<String, SysAclModuleExt> levelAclModuleMap = ArrayListMultimap.create();
        List<SysAclModuleExt> rootList = Lists.newArrayList();

        aclModuleExts.forEach(it -> {
            levelAclModuleMap.put(it.getLevel(), it);
            if (ROOT.equals(it.getLevel())) {
                rootList.add(it);
            }
        });
        Collections.sort(rootList, Comparator.comparingInt(SysAclModule::getSeq));
        transformAclModuleTree(rootList, ROOT, levelAclModuleMap);
        return rootList;
    }

    private void transformAclModuleTree(List<SysAclModuleExt> rootList, String parentLevel, Multimap<String, SysAclModuleExt> levelAclModuleMap) {
        rootList.forEach(it -> {
            String nextLevel = parentLevel + SEPARATOR + it.getId();
            List<SysAclModuleExt> tempList = (List<SysAclModuleExt>) levelAclModuleMap.get(nextLevel);
            if (CollectionUtils.isNotEmpty(tempList)) {
                Collections.sort(tempList, Comparator.comparingInt(SysAclModule::getSeq));
                it.setAclModuleList(tempList);
                transformAclModuleTree(tempList, nextLevel, levelAclModuleMap);
            }
        });
    }

    public List<SysAcl> getRoleAclList(int roleId) {
        List<Integer> aclIdList = sysRoleAclMapper.selectList(new QueryWrapper<SysRoleAcl>().lambda()
                .select(SysRoleAcl::getAclId)
                .eq(SysRoleAcl::getRoleId, roleId))
                .stream()
                .map(it -> it.getAclId())
                .distinct()
                .collect(Collectors.toList());
        if (CollectionUtils.isEmpty(aclIdList)) {
            return Lists.newArrayList();
        }
        return sysAclMapper.selectList(new QueryWrapper<SysAcl>().lambda()
                .in(SysAcl::getId, aclIdList));
    }


    private List<SysAcl> getCurrentUserAclList() {

        int userId = RequestHolder.getCurrentUser().getId();

        if (isSuperAdmin()) {
            return sysAclMapper.selectList(null);
        }

        List<Integer> userRoleIdList = sysRoleUserMapper.selectList(new QueryWrapper<SysRoleUser>()
                .lambda()
                .select(SysRoleUser::getRoleId)
                .eq(SysRoleUser::getId, userId))
                .stream().map(it -> it.getRoleId())
                .collect(Collectors.toList());
        if (CollectionUtils.isEmpty(userRoleIdList)) {
            return Lists.newArrayList();
        }

        List<Integer> userAclIdList = sysRoleAclMapper.selectList(new QueryWrapper<SysRoleAcl>()
                .lambda()
                .select(SysRoleAcl::getAclId)
                .in(SysRoleAcl::getRoleId, userRoleIdList))
                .stream()
                .map(it -> it.getAclId())
                .distinct()
                .collect(Collectors.toList());

        if (CollectionUtils.isEmpty(userAclIdList)) {
            return Lists.newArrayList();
        }

        return sysAclMapper.selectList(new QueryWrapper<SysAcl>().lambda()
                .in(SysAcl::getId, userAclIdList));
    }

    public boolean isSuperAdmin() {
        // 这里是我自己定义了一个假的超级管理员规则，实际中要根据项目进行修改
        // 可以是配置文件获取，可以指定某个用户，也可以指定某个角色
        SysUser sysUser = RequestHolder.getCurrentUser();
        if (sysUser.getMail().contains("admin")) {
            return true;
        }
        return false;
    }

}

