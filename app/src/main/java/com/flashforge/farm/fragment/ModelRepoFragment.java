package com.flashforge.farm.fragment;

import android.content.Context;
import android.view.View;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.TextView;
import android.widget.Toast;

import com.flashforge.farm.MainActivity;
import com.flashforge.farm.R;
import com.flashforge.farm.modelrepo.ModelMetadata;
import com.flashforge.farm.modelrepo.ModelSafety;
import com.flashforge.farm.modelrepo.ModelTransport;
import com.flashforge.farm.modelrepo.TrustStore;
import com.flashforge.farm.navigation.Fragment;
import com.flashforge.farm.slic3r.Slic3rRuntimeError;
import com.flashforge.farm.utils.ViewUtils;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

public class ModelRepoFragment extends Fragment {
    private ModelTransport transport = ModelTransport.unavailable("P2P transport not bundled yet");
    private TrustStore trust;
    private TextView statusView;
    private ArrayAdapter<String> listAdapter;
    private final List<String> downloadedNames = new ArrayList<>();
    private final List<File> downloadedFiles = new ArrayList<>();

    public void setTransport(ModelTransport transport) {
        this.transport = transport;
        refreshList();
    }

    @Override
    public View onCreateView(Context ctx) {
        trust = new TrustStore(new PrefsStorage(), new java.util.HashSet<String>());
        LinearLayout root = new LinearLayout(ctx);
        root.setOrientation(LinearLayout.VERTICAL);
        int pad = ViewUtils.dp(12);
        root.setPadding(pad, pad, pad, pad);

        final EditText ticketInput = new EditText(ctx);
        ticketInput.setHint(ctx.getString(R.string.ModelRepoTicketHint));
        root.addView(ticketInput, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));

        Button fetchBtn = new Button(ctx);
        fetchBtn.setText(ctx.getString(R.string.ModelRepoFetch));
        fetchBtn.setOnClickListener(v -> {
            String ticket = ticketInput.getText().toString().trim();
            if (ticket.isEmpty()) {
                return;
            }
            downloadTicket(ctx, ticket);
        });
        root.addView(fetchBtn);

        statusView = new TextView(ctx);
        statusView.setText(ctx.getString(R.string.ModelRepoIdle));
        root.addView(statusView);

        ListView list = new ListView(ctx);
        listAdapter = new ArrayAdapter<>(ctx, android.R.layout.simple_list_item_1, downloadedNames);
        list.setAdapter(listAdapter);
        list.setOnItemClickListener((parent, view, position, id) -> {
            if (position < downloadedFiles.size()) {
                openInSlicer(downloadedFiles.get(position));
            }
        });
        root.addView(list, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f));
        refreshList();
        return root;
    }

    private void downloadTicket(final Context ctx, final String ticket) {
        setStatus(ctx.getString(R.string.ModelRepoResolving));
        final File dir = new File(ctx.getFilesDir(), "p2p/" + sanitizeTicket(ticket));
        dir.mkdirs();
        try {
            transport.fetch(ticket, dir, new ModelTransport.Listener() {
                @Override
                public void onMetadata(String t, ModelMetadata meta, List<ModelTransport.Entry> entries) {
                    long total = 0;
                    int count = 0;
                    for (ModelTransport.Entry e : entries) {
                        if (!ModelSafety.isAllowedName(e.name)) {
                            fail(t, ctx.getString(R.string.ModelRepoBlockedType, e.name));
                            return;
                        }
                        try {
                            ModelSafety.checkBudget(e.size, total, count);
                        } catch (IllegalArgumentException bad) {
                            fail(t, bad.getMessage());
                            return;
                        }
                        total += e.size;
                        count++;
                    }
                    TrustStore.Level level = trust.level(meta.designer.pubkey);
                    if (level == TrustStore.Level.BLOCKED) {
                        fail(t, ctx.getString(R.string.ModelRepoBlockedPublisher));
                        return;
                    }
                    trust.markSeen(meta.designer.pubkey);
                    if (trust.needsConfirm(level)) {
                        setStatus(ctx.getString(R.string.ModelRepoConfirm, meta.title, meta.designer.name));
                        return;
                    }
                    setStatus(ctx.getString(R.string.ModelRepoDownloading, meta.title));
                }

                @Override
                public void onProgress(String t, long downloadedBytes, long totalBytes) {
                    setStatus(ctx.getString(R.string.ModelRepoProgress,
                            downloadedBytes / 1024, Math.max(totalBytes, 1) / 1024));
                }

                @Override
                public void onComplete(String t, File d) {
                    setStatus(ctx.getString(R.string.ModelRepoDone));
                    refreshList();
                }

                @Override
                public void onError(String t, String msg) {
                    fail(t, msg);
                }

                private void fail(String t, String msg) {
                    try {
                        transport.stop(t);
                    } catch (Exception ignored) {
                    }
                    setStatus(ctx.getString(R.string.ModelRepoFailed, msg));
                }
            });
        } catch (ModelTransport.UnavailableException e) {
            setStatus(ctx.getString(R.string.ModelRepoUnavailable));
        }
    }

    private static String sanitizeTicket(String ticket) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < ticket.length() && sb.length() < 64; i++) {
            char c = ticket.charAt(i);
            if ((c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z') || (c >= '0' && c <= '9')) {
                sb.append(c);
            }
        }
        return sb.length() == 0 ? "default" : sb.toString();
    }

    private void setStatus(final String s) {
        if (statusView == null) {
            return;
        }
        ViewUtils.postOnMainThread(() -> statusView.setText(s));
    }

    private void refreshList() {
        Context ctx = getContext();
        if (ctx == null || listAdapter == null) {
            return;
        }
        File base = new File(ctx.getFilesDir(), "p2p");
        File[] dirs = base.listFiles();
        final List<String> names = new ArrayList<>();
        final List<File> files = new ArrayList<>();
        if (dirs != null) {
            for (File d : dirs) {
                File[] kids = d.listFiles();
                if (kids == null) {
                    continue;
                }
                for (File k : kids) {
                    if (k.isFile() && ModelSafety.isAllowedName(k.getName())) {
                        names.add(k.getName());
                        files.add(k);
                    }
                }
            }
        }
        ViewUtils.postOnMainThread(() -> {
            downloadedNames.clear();
            downloadedNames.addAll(names);
            downloadedFiles.clear();
            downloadedFiles.addAll(files);
            listAdapter.notifyDataSetChanged();
        });
    }

    private void openInSlicer(final File f) {
        final Context ctx = getContext();
        if (ctx == null) {
            return;
        }
        if (!(ctx instanceof MainActivity)) {
            Toast.makeText(ctx, f.getAbsolutePath(), Toast.LENGTH_LONG).show();
            return;
        }
        MainActivity act = (MainActivity) ctx;
        act.getNavigationDelegate().switchSlot(0, () -> {
            com.flashforge.farm.fragment.BedFragment bed =
                    (com.flashforge.farm.fragment.BedFragment) act.getNavigationDelegate().getCurrentFragment();
            if (bed == null) {
                return;
            }
            try {
                bed.loadModel(f);
            } catch (Slic3rRuntimeError e) {
                Toast.makeText(ctx, e.toString(), Toast.LENGTH_LONG).show();
            }
        });
    }

    private static class PrefsStorage implements TrustStore.Storage {
        @Override
        public boolean getBoolean(String key, boolean def) {
            return com.flashforge.farm.utils.Prefs.getPrefs().getBoolean("p2p_trust_" + key, def);
        }

        @Override
        public void putBoolean(String key, boolean value) {
            com.flashforge.farm.utils.Prefs.getPrefs().edit().putBoolean("p2p_trust_" + key, value).apply();
        }

        @Override
        public long getLong(String key, long def) {
            return com.flashforge.farm.utils.Prefs.getPrefs().getLong("p2p_trust_" + key, def);
        }

        @Override
        public void putLong(String key, long value) {
            com.flashforge.farm.utils.Prefs.getPrefs().edit().putLong("p2p_trust_" + key, value).apply();
        }
    }
}
