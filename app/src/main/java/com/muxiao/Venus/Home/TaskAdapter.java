package com.muxiao.Venus.Home;

import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import com.google.android.material.progressindicator.LinearProgressIndicator;
import com.google.android.material.textview.MaterialTextView;
import com.muxiao.Venus.R;

import java.util.List;

public class TaskAdapter extends RecyclerView.Adapter<TaskAdapter.TaskViewHolder> {

    private final List<TaskItem> taskList;

    public TaskAdapter(List<TaskItem> taskList) {
        this.taskList = taskList;
    }

    @NonNull
    @Override
    public TaskViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View view = LayoutInflater.from(parent.getContext()).inflate(R.layout.item_home_task_list, parent, false);
        return new TaskViewHolder(view);
    }

    @Override
    public void onBindViewHolder(@NonNull TaskViewHolder holder, int position) {
        TaskItem task = taskList.get(position);

        holder.taskName.setText(task.getName());

        if (task.isError()) {// 错误.
            holder.statusIcon.setImageResource(R.drawable.ic_error);
            holder.taskProgress.setVisibility(View.GONE);
            holder.taskProgress.setIndeterminate(false);
        } else if (task.isInProgress()) {  // 正在进行中
            holder.statusIcon.setImageResource(R.drawable.ic_pending);
            holder.taskProgress.setVisibility(View.VISIBLE);
            holder.taskProgress.setIndeterminate(true);
        } else if (task.isCompleted()) { // 已完成
            holder.statusIcon.setImageResource(R.drawable.ic_check);
            holder.taskProgress.setVisibility(View.GONE);
            holder.taskProgress.setIndeterminate(false);
        }else if(task.isWarning()){ // 警告
            holder.statusIcon.setImageResource(R.drawable.ic_warning);
            holder.taskProgress.setVisibility(View.VISIBLE);
            holder.taskProgress.setIndeterminate(true);
        }else { // 未开始
            holder.statusIcon.setImageResource(R.drawable.ic_pending);
            holder.taskProgress.setVisibility(View.GONE);
            holder.taskProgress.setIndeterminate(false);
        }
    }

    @Override
    public int getItemCount() {
        return taskList.size();
    }

    public static class TaskViewHolder extends RecyclerView.ViewHolder {
        private final MaterialTextView taskName;
        private final LinearProgressIndicator taskProgress;
        private final ImageView statusIcon;

        public TaskViewHolder(@NonNull View itemView) {
            super(itemView);
            taskName = itemView.findViewById(R.id.task_name);
            statusIcon = itemView.findViewById(R.id.status_icon);
            taskProgress = itemView.findViewById(R.id.task_progress);
        }
    }
}