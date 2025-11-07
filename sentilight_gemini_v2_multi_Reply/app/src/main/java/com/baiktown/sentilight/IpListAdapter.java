package com.baiktown.sentilight;

import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.TextView;
import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;
import java.util.List;

public class IpListAdapter extends RecyclerView.Adapter<IpListAdapter.IpViewHolder> {

    private List<String> ipList;
    private final OnIpActionListener listener;

    /** IP 항목 클릭 및 삭제 이벤트를 처리하기 위한 인터페이스 */
    public interface OnIpActionListener {
        void onDeleteClick(String ipAddress);
        // void onItemClick(String ipAddress); // 필요한 경우 아이템 클릭 이벤트 추가 가능
    }

    public IpListAdapter(List<String> ipList, OnIpActionListener listener) {
        this.ipList = ipList;
        this.listener = listener;
    }

    @NonNull
    @Override
    public IpViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        // item_ip_address.xml 레이아웃을 인플레이트합니다.
        View view = LayoutInflater.from(parent.getContext())
                .inflate(R.layout.item_ip_address, parent, false);
        return new IpViewHolder(view);
    }

    @Override
    public void onBindViewHolder(@NonNull IpViewHolder holder, int position) {
        final String ipAddress = ipList.get(position);
        holder.textViewIpAddress.setText(ipAddress);

        // 삭제 버튼 클릭 이벤트 설정
        holder.buttonDeleteIp.setOnClickListener(v -> {
            if (listener != null) {
                listener.onDeleteClick(ipAddress);
            }
        });
    }

    @Override
    public int getItemCount() {
        return ipList.size();
    }

    /** 외부에서 데이터 변경 시 호출하여 RecyclerView를 업데이트합니다. */
    public void updateList(List<String> newList) {
        this.ipList = newList;
        notifyDataSetChanged();
    }

    /** 개별 항목을 위한 ViewHolder 클래스 */
    static class IpViewHolder extends RecyclerView.ViewHolder {
        final TextView textViewIpAddress;
        final Button buttonDeleteIp;

        IpViewHolder(View itemView) {
            super(itemView);
            textViewIpAddress = itemView.findViewById(R.id.textViewIpAddress);
            buttonDeleteIp = itemView.findViewById(R.id.buttonDeleteIp);
        }
    }
}