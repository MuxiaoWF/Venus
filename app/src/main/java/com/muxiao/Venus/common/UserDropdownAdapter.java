package com.muxiao.Venus.common;

import android.content.Context;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ArrayAdapter;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.muxiao.Venus.R;
import com.muxiao.Venus.User.AvatarStore;

import java.util.List;

/**
 * 账号选择下拉适配器：展开列表每项展示「头像 + 用户名」。
 * 头像复用账号体系的 {@link AvatarStore}（有自定义头像则 Glide 圆形裁剪，
 * 否则默认人像图标 + 主题着色），与账号行/首页视觉同一套。
 * 数据仍为用户名字符串：getItem / getItemAtPosition 返回 String，
 * 各调用方的 OnItemClickListener 取值逻辑无需改动。
 * 使用处：LinkFragment.updateDropdown()、HomeFragment 初始化与 onResume 的 user_dropdown。
 */
public class UserDropdownAdapter extends ArrayAdapter<String> {

    private final Context context;

    public UserDropdownAdapter(@NonNull Context context, @NonNull List<String> usernames) {
        // 注意：不能把 item_user_dropdown（根为 LinearLayout）作为 ArrayAdapter 主 resource——
        // 基类 getView 默认会将其强转 TextView，ListPopupWindow 量高/渲染走 getView 时直接崩溃
        // （ArrayAdapter requires the resource ID to be a TextView）。
        // 主 resource 用系统单行 TextView 兜底，getView/getDropDownView 均重写为自定义 item 视图。
        super(context, android.R.layout.simple_dropdown_item_1line, usernames);
        this.context = context;
    }

    @NonNull
    @Override
    public View getView(int position, @Nullable View convertView, @NonNull ViewGroup parent) {
        // MaterialAutoCompleteTextView 的下拉在某些路径（buildDropDown 量高等）走 getView 而非
        // getDropDownView，两者必须返回同一自定义视图，否则基类 getView 会崩溃或丢失头像。
        return createUserItem(convertView, parent, position);
    }

    @NonNull
    @Override
    public View getDropDownView(int position, @Nullable View convertView, @NonNull ViewGroup parent) {
        return createUserItem(convertView, parent, position);
    }

    /**
     * 构建「头像 + 用户名」下拉项：头像复用账号体系 AvatarStore。
     */
    @NonNull
    private View createUserItem(@Nullable View convertView, @NonNull ViewGroup parent, int position) {
        View item = convertView;
        if (item == null) {
            item = LayoutInflater.from(context).inflate(R.layout.item_user_dropdown, parent, false);
        }
        String username = getItem(position);
        ImageView avatar = item.findViewById(R.id.item_user_avatar);
        TextView name = item.findViewById(R.id.item_user_name);
        name.setText(username);
        // username 为 null/空时 loadInto 内部按默认态处理
        AvatarStore.loadInto(context, username, avatar);
        return item;
    }
}
