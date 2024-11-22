package com.rickey.backend.controller;


import cn.hutool.core.lang.UUID;
import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONUtil;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.extension.plugins.pagination.PageDTO;
import com.rickey.backend.model.dto.user.*;
import com.rickey.backend.model.vo.UserVO;
import com.rickey.backend.service.UserService;
import com.rickey.backend.utils.RedisUtil;
import com.rickey.common.common.BaseResponse;
import com.rickey.common.common.DeleteRequest;
import com.rickey.common.common.ErrorCode;
import com.rickey.common.exception.BusinessException;
import com.rickey.common.model.entity.User;
import com.rickey.common.utils.CookieUtil;
import com.rickey.common.utils.ResultUtils;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.BeanUtils;
import org.springframework.util.DigestUtils;
import org.springframework.web.bind.annotation.*;

import javax.annotation.Resource;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.security.NoSuchAlgorithmException;
import java.util.List;
import java.util.stream.Collectors;

/**
 * 用户接口
 */
@Slf4j
@RestController
@RequestMapping("/user")
public class UserController {

    @Resource
    private UserService userService;

    @Resource
    private RedisUtil redisUtil;

    /**
     * 盐值，混淆密码
     */
    private static final String SALT = "rickey";

    // region 登录相关

    /**
     * 用户注册
     *
     * @param userRegisterRequest
     * @return
     */
    @PostMapping("/register")
    public BaseResponse<Long> userRegister(@RequestBody UserRegisterRequest userRegisterRequest) {
        if (userRegisterRequest == null) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR);
        }
        String userAccount = userRegisterRequest.getUserAccount();
        String userPassword = userRegisterRequest.getUserPassword();
        String checkPassword = userRegisterRequest.getCheckPassword();
        if (StringUtils.isAnyBlank(userAccount, userPassword, checkPassword)) {
            return null;
        }
        long result = userService.userRegister(userAccount, userPassword, checkPassword);
        return ResultUtils.success(result);
    }

    /**
     * 用户登录
     *
     * @param userLoginRequest
     * @param response
     * @return
     */
    @PostMapping("/login")
    public BaseResponse<User> userLogin(@RequestBody UserLoginRequest userLoginRequest, HttpServletResponse response) throws NoSuchAlgorithmException {
        log.info("检测到登录请求");
        if (userLoginRequest == null) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR);
        }
        String userAccount = userLoginRequest.getUserAccount();
        System.out.println("userAccount = " + userAccount);
        String userPassword = userLoginRequest.getUserPassword();
        System.out.println("userPassword = " + userPassword);
        if (StringUtils.isAnyBlank(userAccount, userPassword)) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR);
        }
        // 调用服务层进行用户校验
        User user = userService.userLogin(userAccount, userPassword);
        // 生成新的 Token
        String newToken = UUID.randomUUID().toString();
        log.info("生成的新 Token：{}", newToken);
        // 存储到 Redis（单点登录，清除旧的 Token）
        String redisKey = "token:user:" + user.getId();
        String oldToken = (String) redisUtil.get(redisKey);
        System.out.println("oldToken = " + oldToken);
        if (oldToken != null) {
            boolean del = redisUtil.del("session:" + oldToken);// 删除旧 Token 对应的用户数据
            System.out.println("del = " + del);
        }
        redisUtil.set(redisKey, newToken, 600); // userId + token
        redisUtil.set("session:" + newToken, JSONUtil.toJsonStr(user), 600); // token + userInfo

        CookieUtil.writeLoginToken(newToken, response);
        log.info("newToken : {}", newToken);

        return ResultUtils.success(user);
    }

    /**
     * 用户注销
     *
     * @param request
     * @return
     */
    @PostMapping("/logout")
    public BaseResponse<Boolean> userLogout(HttpServletRequest request, HttpServletResponse response) {
        //读取sessionID
        String loginToken = CookieUtil.readLoginToken(request);
        if (StrUtil.isBlank(loginToken)) {
            return ResultUtils.error(400, "用户未登录，不可注销");
        }
        //删除session
        CookieUtil.deleteLoginToken(request, response);
        String userId = request.getHeader("userId");
        String redisKey = "token:user:" + userId;
        //删除缓存
        redisUtil.del("session:" + loginToken);
        redisUtil.del(redisKey);
        if (request == null) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR);
        }
        return ResultUtils.success(true);
    }

    /**
     * 获取当前登录用户
     *
     * @param request
     * @return
     */
    @GetMapping("/get/login")
    public BaseResponse<UserVO> getLoginUser(HttpServletRequest request) {
        //读取sessionID
        String loginToken = CookieUtil.readLoginToken(request);
        if (StrUtil.isBlank(loginToken)) {
            return ResultUtils.error(402, "用户未登录");
        }
        //从Redis中获取用户的json数据
        String userJson = (String) redisUtil.get("session:" + loginToken);
        // log.info("getLoginUser:UserJson:{}",userJson);
        //json转换成Use对象
        User user = JSONUtil.toBean(userJson, User.class);
        UserVO userVO = new UserVO();
        BeanUtils.copyProperties(user, userVO);
        // log.info("getLoginUser:UserVo:{}",userJson);
        return ResultUtils.success(userVO);
    }

    /**
     * 创建用户
     *
     * @param userAddRequest
     * @param request
     * @return
     */
    @PostMapping("/add")
    public BaseResponse<Long> addUser(@RequestBody UserAddRequest userAddRequest, HttpServletRequest request) {
        if (userAddRequest == null) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR);
        }
        User user = new User();
        BeanUtils.copyProperties(userAddRequest, user);
        boolean result = userService.save(user);
        if (!result) {
            throw new BusinessException(ErrorCode.OPERATION_ERROR);
        }
        return ResultUtils.success(user.getId());
    }

    /**
     * 删除用户
     *
     * @param deleteRequest
     * @param request
     * @return
     */
    @PostMapping("/delete")
    public BaseResponse<Boolean> deleteUser(@RequestBody DeleteRequest deleteRequest, HttpServletRequest request) {
        if (deleteRequest == null || deleteRequest.getId() <= 0) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR);
        }
        boolean b = userService.removeById(deleteRequest.getId());
        return ResultUtils.success(b);
    }

    /**
     * 更新用户
     *
     * @param userUpdateRequest
     * @param request
     * @return
     */
    @PostMapping("/update")
    public BaseResponse<Boolean> updateUser(@RequestBody UserUpdateRequest userUpdateRequest, HttpServletRequest request, HttpServletResponse response) {
        if (userUpdateRequest == null || userUpdateRequest.getId() == null) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR);
        }
        User user = new User();
        BeanUtils.copyProperties(userUpdateRequest, user);
        String encryptPassword = DigestUtils.md5DigestAsHex((SALT + user.getUserPassword()).getBytes());
        user.setUserPassword(encryptPassword);
        boolean result = userService.updateById(user);
        //读取sessionID
        String loginToken = CookieUtil.readLoginToken(request);
        //删除session
        CookieUtil.deleteLoginToken(request, response);
        String userId = request.getHeader("userId");
        String redisKey = "token:user:" + userId;
        //删除缓存
        redisUtil.del("session:" + loginToken);
        redisUtil.del(redisKey);
        return ResultUtils.success(result);
    }

    /**
     * 根据 id 获取用户
     *
     * @param id
     * @param request
     * @return
     */
    @GetMapping("/get")
    public BaseResponse<UserVO> getUserById(int id, HttpServletRequest request) {
        if (id <= 0) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR);
        }
        User user = userService.getById(id);
        UserVO userVO = new UserVO();
        BeanUtils.copyProperties(user, userVO);
        return ResultUtils.success(userVO);
    }

    /**
     * 获取用户列表
     *
     * @param userQueryRequest
     * @param request
     * @return
     */
    @GetMapping("/list")
    public BaseResponse<List<UserVO>> listUser(UserQueryRequest userQueryRequest, HttpServletRequest request) {
        User userQuery = new User();
        if (userQueryRequest != null) {
            BeanUtils.copyProperties(userQueryRequest, userQuery);
        }
        QueryWrapper<User> queryWrapper = new QueryWrapper<>(userQuery);
        List<User> userList = userService.list(queryWrapper);
        List<UserVO> userVOList = userList.stream().map(user -> {
            UserVO userVO = new UserVO();
            BeanUtils.copyProperties(user, userVO);
            return userVO;
        }).collect(Collectors.toList());
        return ResultUtils.success(userVOList);
    }

    /**
     * 分页获取用户列表
     *
     * @param userQueryRequest
     * @param request
     * @return
     */
    @GetMapping("/list/page")
    public BaseResponse<Page<UserVO>> listUserByPage(UserQueryRequest userQueryRequest, HttpServletRequest request) {
        long current = 1;
        long size = 10;
        User userQuery = new User();
        if (userQueryRequest != null) {
            BeanUtils.copyProperties(userQueryRequest, userQuery);
            current = userQueryRequest.getCurrent();
            size = userQueryRequest.getPageSize();
        }
        QueryWrapper<User> queryWrapper = new QueryWrapper<>(userQuery);
        Page<User> userPage = userService.page(new Page<>(current, size), queryWrapper);
        Page<UserVO> userVOPage = new PageDTO<>(userPage.getCurrent(), userPage.getSize(), userPage.getTotal());
        List<UserVO> userVOList = userPage.getRecords().stream().map(user -> {
            UserVO userVO = new UserVO();
            BeanUtils.copyProperties(user, userVO);
            return userVO;
        }).collect(Collectors.toList());
        userVOPage.setRecords(userVOList);
        return ResultUtils.success(userVOPage);
    }

    // endregion
}
