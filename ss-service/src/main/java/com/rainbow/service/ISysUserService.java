package com.rainbow.service;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.rainbow.domain.SysUser;
import com.rainbow.vo.Response;
import com.rainbow.vo.SysUserReq;

public interface ISysUserService {

    Response<String> addSysUser(SysUserReq userReq);

    void deleteByUser();

    Response updateUser(SysUserReq userReq);

    Response<IPage<SysUser>> getSysUser();

}
