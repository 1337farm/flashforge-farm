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
import com.flashforge.farm.modelrepo.IrohModelTransport;
import com.flashforge.farm.modelrepo.ModelMetadata;
import com.flashforge.farm.modelrepo.ModelSafety;
import com.flashforge.farm.modelrepo.ModelTransport;
import com.flashforge.farm.modelrepo.SearchClient;
import com.flashforge.farm.modelrepo.TrustStore;
import com.flashforge.farm.modelrepo.moderation.LabelAggregator;
import com.flashforge.farm.modelrepo.verify.ModelVerifier;
import com.flashforge.farm.modelrepo.verify.Verdict;
import com.flashforge.farm.navigation.Fragment;
import com.flashforge.farm.slic3r.Slic3rRuntimeError;
import com.flashforge.farm.utils.ViewUtils;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

public class ModelRepoFragment extends Fragment {
    private IrohModelTransport irohTransport;
    private SearchClient searchClient;
    private ModelTransport transport;
    private TrustStore trust;
    private LabelAggregator labels;

    private static final String[] DEFAULT_MODERATORS = new String[0];
    private TextView statusView;
    private ArrayAdapter<String> listAdapter;
    private final List<String> downloadedNames = new ArrayList<>();
    private final List<File> downloadedFiles = new ArrayList<>();
    private String pendingTicket;
    private String pendingPubkey;

    private EditText searchInput;
    private ListView searchResultsList;
    private ArrayAdapter<String> searchAdapter;
    private final List<SearchClient.SearchResult> searchResults = new ArrayList<>();

    public void setTransport(ModelTransport transport) {
        this.transport = transport;
        refreshList();
    }

    public void setIrohTransport(IrohModelTransport irohTransport) {
        this.irohTransport = irohTransport;
        if (irohTransport != null) {
            try {
                this.searchClient = new SearchClient(irohTransport);
            } catch (Exception e) {
                e.printStackTrace();
            }
        }
    }

    private void ensureIroh(Context ctx) {
        if (irohTransport != null) {
            return;
        }
        try {
            IrohModelTransport t = com.flashforge.farm.modelrepo.P2pManager.transport(ctx);
            setIrohTransport(t);
        } catch (Exception e) {
            setStatus("P2P unavailable: " + e.getMessage());
        }
    }

    @Override
    public void subscribeModerator(String ownerPubkeyHex) {
        if (labels != null && ownerPubkeyHex != null) {
            labels.subscribe(ownerPubkeyHex);
        }
    }

    public void unsubscribeModerator(String ownerPubkeyHex) {
        if (labels != null && ownerPubkeyHex != null) {
            labels.unsubscribe(ownerPubkeyHex);
        }
    }

    public void updateModeratorFeed(byte[] rawFeed) {
        if (labels == null) {
            throw new IllegalStateException("not initialized");
        }
        labels.updateFeed(rawFeed);
    }

    @Override
    public View onCreateView(Context ctx) {
        ensureIroh(ctx);
        trust = new TrustStore(new PrefsStorage(), new java.util.HashSet<String>());
        labels = new LabelAggregator(trust);
        for (String mod : DEFAULT_MODERATORS) {
            labels.subscribe(mod);
        }
        LinearLayout root = new LinearLayout(ctx);
        root.setOrientation(LinearLayout.VERTICAL);
        int pad = ViewUtils.dp(12);
        root.setPadding(pad, pad, pad, pad);

        // Search section
        TextView searchLabel = new TextView(ctx);
        searchLabel.setText("Search Models");
        searchLabel.setTextSize(16);
        searchLabel.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        root.addView(searchLabel);

        searchInput = new EditText(ctx);
        searchInput.setHint("Search by keyword (e.g., calibration, benchy, cube)");
        root.addView(searchInput, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));

        Button searchBtn = new Button(ctx);
        searchBtn.setText("Search");
        searchBtn.setOnClickListener(v -> performSearch(ctx));
        root.addView(searchBtn);

        searchResultsList = new ListView(ctx);
        searchAdapter = new ArrayAdapter<>(ctx, android.R.layout.simple_list_item_1, new ArrayList<>());
        searchResultsList.setAdapter(searchAdapter);
        searchResultsList.setOnItemClickListener((parent, view, position, id) -> {
            if (position < searchResults.size()) {
                SearchClient.SearchResult result = searchResults.get(position);
                downloadModel(ctx, result.modelHash, result.title);
            }
        });
        root.addView(searchResultsList, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 0.4f));

        // Direct ticket download section
        View divider = new View(ctx);
        divider.setLayoutParams(new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, ViewUtils.dp(1)));
        divider.setBackgroundColor(0xFF444444);
        root.addView(divider);

        TextView ticketLabel = new TextView(ctx);
        ticketLabel.setText("Or Download by Ticket");
        ticketLabel.setTextSize(16);
        ticketLabel.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        root.addView(ticketLabel);

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
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 0.6f));

        refreshList();
        return root;
    }

    private void performSearch(Context ctx) {
        String query = searchInput.getText().toString().trim();
        if (query.isEmpty()) {
            return;
        }

        if (searchClient == null) {
            setStatus("Search unavailable: Iroh not initialized");
            return;
        }

        setStatus("Searching for: " + query + "...");

        searchClient.searchAsync(query, new SearchClient.SearchCallback() {
            @Override
            public void onResults(List<SearchClient.SearchResult> results) {
                searchResults.clear();
                searchResults.addAll(results);

                List<String> displayNames = new ArrayList<>();
                for (SearchClient.SearchResult r : results) {
                    String desc = r.description.isEmpty() ? "" : " - " + r.description.substring(0, Math.min(50, r.description.length()));
                    String by = searchClient.profileLabel(r.designerName, r.designerPubkey);
                    displayNames.add(r.title + desc + "\nby " + by + " [" + r.category + "]");
                }

                ViewUtils.postOnMainThread(() -> {
                    searchAdapter.clear();
                    searchAdapter.addAll(displayNames);
                    searchAdapter.notifyDataSetChanged();
                    setStatus("Found " + results.size() + " result(s) for: " + query);
                });
            }

            @Override
            public void onError(String error) {
                ViewUtils.postOnMainThread(() -> {
                    setStatus("Search failed: " + error);
                });
            }
        });
    }

    private void downloadModel(Context ctx, String modelHash, String title) {
        setStatus("Downloading: " + title + "...");

        if (irohTransport != null) {
            File dir = new File(ctx.getFilesDir(), "p2p/" + modelHash.substring(0, 16));
            dir.mkdirs();

            try {
                irohTransport.fetch(modelHash, dir, new ModelTransport.Listener() {
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
                        if (level == TrustStore.Level.BLOCKED || labelsBlocked(meta.designer.pubkey)) {
                            fail(t, ctx.getString(R.string.ModelRepoBlockedPublisher));
                            return;
                        }
                        trust.markSeen(meta.designer.pubkey);
                        if (trust.needsConfirm(level)) {
                            try {
                                if (irohTransport != null) irohTransport.stop(t);
                            } catch (Exception ignored) {
                            }
                            pendingTicket = t;
                            pendingPubkey = meta.designer.pubkey;
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
                        Verdict v = postVerify(d);
                        if (!v.allow) {
                            fail(t, v.detail.isEmpty() ? v.reason.name() : v.detail);
                            refreshList();
                            return;
                        }
                        pendingTicket = null;
                        pendingPubkey = null;
                        setStatus(ctx.getString(R.string.ModelRepoDone));
                        refreshList();
                    }

                    @Override
                    public void onError(String t, String msg) {
                        fail(t, msg);
                    }

                    private void fail(String t, String msg) {
                        try {
                            if (irohTransport != null) irohTransport.stop(t);
                        } catch (Exception ignored) {
                        }
                        pendingTicket = null;
                        pendingPubkey = null;
                        setStatus(ctx.getString(R.string.ModelRepoFailed, msg));
                    }
                });
            } catch (ModelTransport.UnavailableException e) {
                setStatus(ctx.getString(R.string.ModelRepoUnavailable));
            }
        } else if (transport != null) {
            downloadTicket(ctx, modelHash);
        }
    }

    private void downloadTicket(final Context ctx, final String ticket) {
        setStatus(ctx.getString(R.string.ModelRepoResolving));
        if (ticket.equals(pendingTicket) && pendingPubkey != null) {
            trust.setTrusted(pendingPubkey, true);
            pendingTicket = null;
            pendingPubkey = null;
        }
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
                    if (level == TrustStore.Level.BLOCKED || labelsBlocked(meta.designer.pubkey)) {
                        fail(t, ctx.getString(R.string.ModelRepoBlockedPublisher));
                        return;
                    }
                    trust.markSeen(meta.designer.pubkey);
                    if (trust.needsConfirm(level)) {
                        try {
                            transport.stop(t);
                        } catch (Exception ignored) {
                        }
                        pendingTicket = ticket;
                        pendingPubkey = meta.designer.pubkey;
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
                    Verdict v = postVerify(d);
                    if (!v.allow) {
                        fail(t, v.detail.isEmpty() ? v.reason.name() : v.detail);
                        refreshList();
                        return;
                    }
                    pendingTicket = null;
                    pendingPubkey = null;
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
                    pendingTicket = null;
                    pendingPubkey = null;
                    setStatus(ctx.getString(R.string.ModelRepoFailed, msg));
                }
            });
        } catch (ModelTransport.UnavailableException e) {
            setStatus(ctx.getString(R.string.ModelRepoUnavailable));
        }
    }

    private Verdict postVerify(File dir) {
        if (dir == null || !dir.isDirectory()) {
            return Verdict.deny(Verdict.Reason.HASH_MISMATCH, "missing dir");
        }
        File[] kids = dir.listFiles();
        if (kids == null || kids.length == 0) {
            return Verdict.deny(Verdict.Reason.BAD_METADATA, "empty download");
        }
        for (File k : kids) {
            if (!k.isFile()) {
                continue;
            }
            Verdict v = ModelVerifier.verifyForPublish(k);
            if (!v.allow) {
                k.delete();
                return v;
            }
        }
        return Verdict.ok();
    }

    private boolean labelsBlocked(String pubkeyHex) {
        return labels != null && labels.isBlocked(pubkeyHex);
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
            com.flashforge.farm.navigation.Fragment cur =
                    act.getNavigationDelegate().getCurrentFragment();
            if (!(cur instanceof com.flashforge.farm.fragment.BedFragment)) {
                return;
            }
            com.flashforge.farm.fragment.BedFragment bed =
                    (com.flashforge.farm.fragment.BedFragment) cur;
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