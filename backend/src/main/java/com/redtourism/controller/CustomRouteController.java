package com.redtourism.controller;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.redtourism.common.Constants;
import com.redtourism.common.Result;
import com.redtourism.entity.User;
import com.redtourism.entity.UserCustomRoute;
import com.redtourism.mapper.UserCustomRouteMapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

import javax.servlet.http.HttpSession;
import java.util.Date;
import java.util.List;

@RestController
@RequestMapping("/api/customRoute")
public class CustomRouteController {

    @Autowired
    private UserCustomRouteMapper routeMapper;

    private User getUser(HttpSession session) {
        return (User) session.getAttribute(Constants.SESSION_USER);
    }

    @GetMapping("/list")
    public Result<List<UserCustomRoute>> list(HttpSession session) {
        User user = getUser(session);
        if (user == null) return Result.error(401, "请先登录");
        LambdaQueryWrapper<UserCustomRoute> w = new LambdaQueryWrapper<>();
        w.eq(UserCustomRoute::getUserId, user.getId())
         .orderByDesc(UserCustomRoute::getCreateTime);
        return Result.success(routeMapper.selectList(w));
    }

    @GetMapping("/detail")
    public Result<UserCustomRoute> detail(@RequestParam Long id, HttpSession session) {
        User user = getUser(session);
        if (user == null) return Result.error(401, "请先登录");
        UserCustomRoute r = routeMapper.selectById(id);
        if (r == null || !r.getUserId().equals(user.getId())) return Result.error("线路不存在");
        return Result.success(r);
    }

    @GetMapping("/save")
    public Result<UserCustomRoute> save(
            @RequestParam(required = false) Long id,
            @RequestParam String name,
            @RequestParam(required = false) String description,
            @RequestParam(defaultValue = "1") Integer days,
            @RequestParam(required = false, defaultValue = "[]") String spotData,
            HttpSession session) {
        User user = getUser(session);
        if (user == null) return Result.error(401, "请先登录");

        if (id != null) {
            // 仅草稿/已驳回可修改；审核中需先撤回，已通过不可修改。
            // 条件更新保证与并发提交/撤回/审核操作互斥，只有一个能生效。
            LambdaUpdateWrapper<UserCustomRoute> uw = new LambdaUpdateWrapper<>();
            uw.eq(UserCustomRoute::getId, id)
              .eq(UserCustomRoute::getUserId, user.getId())
              .and(w -> w.eq(UserCustomRoute::getStatus, "DRAFT")
                         .or().eq(UserCustomRoute::getStatus, "REJECTED")
                         .or().isNull(UserCustomRoute::getStatus))
              .set(UserCustomRoute::getName, name)
              .set(UserCustomRoute::getDescription, description)
              .set(UserCustomRoute::getDays, days)
              .set(UserCustomRoute::getSpotData, spotData)
              .set(UserCustomRoute::getUpdateTime, new Date());
            int rows = routeMapper.update(null, uw);
            if (rows == 0) {
                UserCustomRoute cur = routeMapper.selectById(id);
                if (cur == null || !cur.getUserId().equals(user.getId()))
                    return Result.error("线路不存在");
                if ("SUBMITTED".equals(cur.getStatus()))
                    return Result.error("线路正在审核中，请先撤回到草稿再修改");
                if ("APPROVED".equals(cur.getStatus()))
                    return Result.error("线路已通过审核并纳入推荐，不可修改");
                return Result.error("当前状态不可修改");
            }
            return Result.success(routeMapper.selectById(id));
        }

        UserCustomRoute route = new UserCustomRoute();
        route.setUserId(user.getId());
        route.setName(name);
        route.setDescription(description);
        route.setDays(days);
        route.setSpotData(spotData);
        route.setStatus("DRAFT");
        route.setCreateTime(new Date());
        route.setUpdateTime(new Date());
        routeMapper.insert(route);
        return Result.success(route);
    }

    /** 用户提交自定义线路供管理员审核（纳入官方推荐） */
    @GetMapping("/submitForReview")
    public Result<String> submitForReview(@RequestParam Long id, HttpSession session) {
        User user = getUser(session);
        if (user == null) return Result.error(401, "请先登录");
        // 仅草稿/已驳回可提交；提交时清空历史驳回原因，避免残留显示。
        // 条件更新保证并发重复提交/并发撤回只有一个成功。
        LambdaUpdateWrapper<UserCustomRoute> uw = new LambdaUpdateWrapper<>();
        uw.eq(UserCustomRoute::getId, id)
          .eq(UserCustomRoute::getUserId, user.getId())
          .and(w -> w.eq(UserCustomRoute::getStatus, "DRAFT")
                     .or().eq(UserCustomRoute::getStatus, "REJECTED")
                     .or().isNull(UserCustomRoute::getStatus))
          .set(UserCustomRoute::getStatus, "SUBMITTED")
          .set(UserCustomRoute::getRejectReason, null)
          .set(UserCustomRoute::getUpdateTime, new Date());
        int rows = routeMapper.update(null, uw);
        if (rows == 0) {
            UserCustomRoute cur = routeMapper.selectById(id);
            if (cur == null || !cur.getUserId().equals(user.getId()))
                return Result.error("线路不存在");
            if ("SUBMITTED".equals(cur.getStatus()))
                return Result.error("线路已在审核中，请勿重复提交");
            if ("APPROVED".equals(cur.getStatus()))
                return Result.error("线路已通过审核，无需重复提交");
            return Result.error("当前状态不可提交");
        }
        return Result.success("已提交审核，等待管理员处理", null);
    }

    /** 用户撤回审核中的线路回到草稿（仅变更状态，线路名称与已选景点保持原样） */
    @GetMapping("/withdraw")
    public Result<String> withdraw(@RequestParam Long id, HttpSession session) {
        User user = getUser(session);
        if (user == null) return Result.error(401, "请先登录");
        // 条件更新：仅审核中可撤回；与并发提交/审核操作互斥
        LambdaUpdateWrapper<UserCustomRoute> uw = new LambdaUpdateWrapper<>();
        uw.eq(UserCustomRoute::getId, id)
          .eq(UserCustomRoute::getUserId, user.getId())
          .eq(UserCustomRoute::getStatus, "SUBMITTED")
          .set(UserCustomRoute::getStatus, "DRAFT")
          .set(UserCustomRoute::getUpdateTime, new Date());
        int rows = routeMapper.update(null, uw);
        if (rows == 0) {
            UserCustomRoute cur = routeMapper.selectById(id);
            if (cur == null || !cur.getUserId().equals(user.getId()))
                return Result.error("线路不存在");
            if ("APPROVED".equals(cur.getStatus()))
                return Result.error("线路已通过审核，无法撤回");
            if ("REJECTED".equals(cur.getStatus()))
                return Result.error("线路已被驳回，无需撤回");
            return Result.error("线路不在审核中，无需撤回");
        }
        return Result.success("已撤回到草稿，可修改后重新提交", null);
    }

    @GetMapping("/delete")
    public Result<String> delete(@RequestParam Long id, HttpSession session) {
        User user = getUser(session);
        if (user == null) return Result.error(401, "请先登录");
        // 仅草稿/已驳回可删除；审核中需先撤回，已通过不可删除
        LambdaQueryWrapper<UserCustomRoute> qw = new LambdaQueryWrapper<>();
        qw.eq(UserCustomRoute::getId, id)
          .eq(UserCustomRoute::getUserId, user.getId())
          .and(w -> w.eq(UserCustomRoute::getStatus, "DRAFT")
                     .or().eq(UserCustomRoute::getStatus, "REJECTED")
                     .or().isNull(UserCustomRoute::getStatus));
        int rows = routeMapper.delete(qw);
        if (rows == 0) {
            UserCustomRoute cur = routeMapper.selectById(id);
            if (cur == null || !cur.getUserId().equals(user.getId()))
                return Result.error("线路不存在");
            if ("SUBMITTED".equals(cur.getStatus()))
                return Result.error("线路正在审核中，请先撤回再删除");
            if ("APPROVED".equals(cur.getStatus()))
                return Result.error("线路已通过审核并纳入推荐，不可删除");
            return Result.error("当前状态不可删除");
        }
        return Result.success("删除成功", null);
    }
}
