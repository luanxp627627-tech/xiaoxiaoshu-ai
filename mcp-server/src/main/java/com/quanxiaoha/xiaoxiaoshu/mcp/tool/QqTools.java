package com.quanxiaoha.xiaoxiaoshu.mcp.tool;

import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.util.List;
import java.util.regex.Pattern;

/**
 * @author: 犬小哈
 * @description: QQ 信息相关工具（演示数据，不依赖真实 QQ 接口）
 **/
@Component
public class QqTools {

    private static final Pattern QQ_PATTERN = Pattern.compile("^\\d{5,12}$");
    private static final List<String> CITIES = List.of(
            "北京", "上海", "广州", "深圳", "杭州", "成都", "武汉", "南京", "西安", "重庆");
    private static final List<String> SIGNS = List.of(
            "这个人很懒，什么都没有留下。",
            "生活明朗，万物可爱。",
            "慢慢来，比较快。",
            "Keep it simple.",
            "今天也要好好吃饭。",
            "在成为更好的自己。",
            "日落尤其温柔，人间皆是浪漫。",
            "正在输入中…");

    @Tool(name = "getQQInfo", description = "根据 QQ 号查询等级、昵称、地区、注册年份、个性签名等资料（演示数据）")
    public String getQQInfo(@ToolParam(description = "QQ 号，5 到 12 位数字") String qq) {
        if (qq == null || !QQ_PATTERN.matcher(qq.strip()).matches()) {
            return "QQ 号格式不正确，应为 5 到 12 位数字。";
        }
        String number = qq.strip();
        int seed = number.hashCode();
        int level = 8 + Math.floorMod(seed, 73);
        int days = 120 + Math.abs(seed % 4200);
        int registerYear = LocalDate.now().minusDays(days).getYear();
        String city = CITIES.get(Math.floorMod(seed, CITIES.size()));
        String sign = SIGNS.get(Math.floorMod(seed / 7, SIGNS.size()));
        String nickname = "小书用户" + number.substring(Math.max(0, number.length() - 4));

        return """
                QQ 号：%s
                昵称：%s
                QQ 等级：%d 级
                注册时间：%d 年（约 %d 天）
                地区：%s
                个性签名：%s
                """.formatted(number, nickname, level, registerYear, days, city, sign).strip();
    }

    @Tool(name = "getQQAvatar", description = "获取 QQ 号对应的头像图片地址，可直接在网页中展示")
    public String getQQAvatar(@ToolParam(description = "QQ 号，5 到 12 位数字") String qq) {
        if (qq == null || !QQ_PATTERN.matcher(qq.strip()).matches()) {
            return "QQ 号格式不正确，应为 5 到 12 位数字。";
        }
        return "https://q1.qlogo.cn/g?b=qq&nk=" + qq.strip() + "&s=640";
    }
}
