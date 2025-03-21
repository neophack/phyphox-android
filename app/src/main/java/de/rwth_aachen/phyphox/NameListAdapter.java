package de.rwth_aachen.phyphox;

import android.content.Context;
import android.content.SharedPreferences;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ArrayAdapter;
import android.widget.ImageView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.Set;

class NameListAdapter extends ArrayAdapter<String> {
    private Context context;
    private ArrayList<String> nameList;
    private SharedPreferences prefs;

    public NameListAdapter(Context context, ArrayList<String> nameList, SharedPreferences prefs) {
        super(context, R.layout.list_item_name, nameList);
        this.context = context;
        this.nameList = nameList;
        this.prefs = prefs;
    }

    @NonNull
    @Override
    public View getView(int position, @Nullable View convertView, @NonNull ViewGroup parent) {
        if (convertView == null) {
            convertView = LayoutInflater.from(context).inflate(R.layout.list_item_name, parent, false);
        }

        TextView textName = convertView.findViewById(R.id.text_name);
        ImageView deleteButton = convertView.findViewById(R.id.delete_button);

        String folderName = nameList.get(position);
        textName.setText(folderName);

        deleteButton.setOnClickListener(v -> {
            nameList.remove(position);
            notifyDataSetChanged();

            // 更新 SharedPreferences
            Set<String> newNameSet = new HashSet<>(nameList);
            prefs.edit().putStringSet("names", newNameSet).apply();

            Toast.makeText(context, "删除：" + folderName, Toast.LENGTH_SHORT).show();
        });

        return convertView;
    }

    public void updateData(ArrayList<String> newList) {
        nameList.clear();
        nameList.addAll(newList);
        notifyDataSetChanged();
    }
}
