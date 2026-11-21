package com.muxiao.Venus.Link;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

import androidx.core.app.RemoteInput;
import androidx.core.content.ContextCompat;

/**
 * 配对通知输入转发器：RemoteInput 结果经此接收器转成 startForegroundService intent 交给服务。
 * 必须用通知输入而非应用内对话框——系统配对弹窗随界面切换即关闭，配对端口随之失效（同 Ascent）。
 */
public class AdbPairingReplyReceiver extends BroadcastReceiver {

    @Override
    public void onReceive(Context context, Intent intent) {
        String action = intent.getAction();
        Intent out = new Intent(context, AdbCaptureService.class);
        if (AdbCaptureService.ACTION_REPLY_SKIP.equals(action)) {
            // 跳过配对，直接连接
            out.setAction(AdbCaptureService.ACTION_REPLY_SKIP);
        } else if (AdbCaptureService.ACTION_REPLY_CONNECT_PORT.equals(action)) {
            CharSequence connectPort = remoteInputText(intent, AdbCaptureService.REPLY_KEY_CONNECT_PORT);
            if (connectPort == null || connectPort.length() == 0) return;
            out.putExtra(AdbCaptureService.EXTRA_CONNECT_PORT, connectPort.toString().trim());
        } else if (AdbCaptureService.ACTION_REPLY_PORT.equals(action)) {
            CharSequence port = remoteInputText(intent, AdbCaptureService.REPLY_KEY_PORT);
            if (port == null || port.length() == 0) return;
            out.putExtra(AdbCaptureService.EXTRA_PAIR_PORT, port.toString().trim());
        } else if (AdbCaptureService.ACTION_REPLY_CODE.equals(action)) {
            CharSequence code = remoteInputText(intent, AdbCaptureService.REPLY_KEY_CODE);
            if (code == null || code.length() == 0) return;
            out.putExtra(AdbCaptureService.EXTRA_PAIR_CODE, code.toString().trim());
            // 端口取 mDNS 最新发现结果，为空则服务报错
            String port = AdbCaptureService.discoveredPairPort;
            if (port != null && !port.isEmpty())
                out.putExtra(AdbCaptureService.EXTRA_PAIR_PORT, port);
        } else {
            return;
        }
        ContextCompat.startForegroundService(context, out);
    }

    private CharSequence remoteInputText(Intent intent, String key) {
        android.os.Bundle results = RemoteInput.getResultsFromIntent(intent);
        return results == null ? null : results.getCharSequence(key);
    }
}
