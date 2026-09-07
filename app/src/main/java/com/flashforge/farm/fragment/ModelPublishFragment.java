package com.flashforge.farm.fragment;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.view.View;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

import com.flashforge.farm.MainActivity;
import com.flashforge.farm.R;
import com.flashforge.farm.modelrepo.IrohModelTransport;
import com.flashforge.farm.modelrepo.ModelMetadata;
import com.flashforge.farm.modelrepo.ModelPublisher;
import com.flashforge.farm.modelrepo.ModelSafety;
import com.flashforge.farm.navigation.Fragment;
import com.flashforge.farm.utils.IOUtils;
import com.flashforge.farm.utils.ViewUtils;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;

public class ModelPublishFragment extends Fragment {
    private static final int REQUEST_PICK_FILE = 1001;
    private static final String TAG = "ModelPublishFragment";

    private IrohModelTransport irohTransport;
    private TextView statusView;
    private EditText titleInput;
    private EditText descriptionInput;
    private EditText designerNameInput;
    private EditText designerPubkeyInput;
    private EditText categoryInput;
    private EditText tagsInput;
    private EditText licenseInput;
    private Spinner licenseSpinner;
    private Button pickFileBtn;
    private Button publishBtn;
    private File selectedFile;
    private String selectedFileName;

    public void setIrohTransport(IrohModelTransport transport) {
        this.irohTransport = transport;
    }

    @Override
    public View onCreateView(Context ctx) {
        if (irohTransport == null) {
            try {
                irohTransport = com.flashforge.farm.modelrepo.P2pManager.transport(ctx);
            } catch (Exception e) {
                Toast.makeText(ctx, "P2P unavailable: " + e.getMessage(), Toast.LENGTH_LONG).show();
            }
        }
        LinearLayout root = new LinearLayout(ctx);
        root.setOrientation(LinearLayout.VERTICAL);
        int pad = ViewUtils.dp(16);
        root.setPadding(pad, pad, pad, pad);

        TextView header = new TextView(ctx);
        header.setText("Publish Model");
        header.setTextSize(24);
        header.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        header.setGravity(android.view.Gravity.CENTER);
        root.addView(header);

        // Title
        titleInput = createInput(ctx, "Title *", "e.g., 3D Benchy");
        root.addView(titleInput);

        // Description
        descriptionInput = createInput(ctx, "Description", "Standard calibration torture test...");
        root.addView(descriptionInput);

        // Designer name
        designerNameInput = createInput(ctx, "Designer Name", "Your name or handle");
        root.addView(designerNameInput);

        // Designer pubkey (optional - will use own if empty)
        designerPubkeyInput = createInput(ctx, "Designer Pubkey (optional)", "Leave empty to use your identity");
        root.addView(designerPubkeyInput);

        // Category
        categoryInput = createInput(ctx, "Category", "calibration, functional, artistic, etc.");
        root.addView(categoryInput);

        // Tags
        tagsInput = createInput(ctx, "Tags (comma-separated)", "calibration, test, benchmark");
        root.addView(tagsInput);

        // License
        TextView licenseLabel = new TextView(ctx);
        licenseLabel.setText("License");
        licenseLabel.setTextSize(16);
        root.addView(licenseLabel);

        licenseSpinner = new Spinner(ctx);
        String[] licenses = {"unspecified", "CC-BY-4.0", "CC-BY-SA-4.0", "CC0-1.0", "GPL-3.0", "MIT", "BSD-3-Clause", "Proprietary"};
        ArrayAdapter<String> licenseAdapter = new ArrayAdapter<>(ctx, android.R.layout.simple_spinner_item, licenses);
        licenseAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        licenseSpinner.setAdapter(licenseAdapter);
        root.addView(licenseSpinner);

        // File picker
        pickFileBtn = new Button(ctx);
        pickFileBtn.setText("Select Model File (.stl, .3mf, .obj, .step)");
        pickFileBtn.setOnClickListener(v -> openFilePicker());
        root.addView(pickFileBtn);

        TextView fileInfo = new TextView(ctx);
        fileInfo.setText("No file selected");
        fileInfo.setTextSize(14);
        fileInfo.setTextColor(0xFF888888);
        root.addView(fileInfo);

        // Publish button
        publishBtn = new Button(ctx);
        publishBtn.setText("Publish to Network");
        publishBtn.setOnClickListener(v -> publishModel(ctx));
        publishBtn.setEnabled(false);
        root.addView(publishBtn);

        statusView = new TextView(ctx);
        statusView.setText("Ready to publish");
        statusView.setTextSize(14);
        root.addView(statusView);

        return root;
    }

    private EditText createInput(Context ctx, String label, String hint) {
        LinearLayout container = new LinearLayout(ctx);
        container.setOrientation(LinearLayout.VERTICAL);

        TextView tv = new TextView(ctx);
        tv.setText(label);
        tv.setTextSize(14);
        container.addView(tv);

        EditText et = new EditText(ctx);
        et.setHint(hint);
        container.addView(et);

        return et;
    }

    private void openFilePicker() {
        Context ctx = getContext();
        if (!(ctx instanceof MainActivity)) {
            Toast.makeText(ctx, "File picker needs the main screen", Toast.LENGTH_SHORT).show();
            return;
        }
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("*/*");
        intent.putExtra(Intent.EXTRA_MIME_TYPES, new String[] {
            "application/sla",
            "model/stl",
            "model/3mf",
            "model/obj",
            "application/octet-stream"
        });
        ((MainActivity) ctx).startActivityForResult(intent, MainActivity.REQUEST_CODE_IMPORT_MODEL);
    }

    public void handlePickedFile(Intent data) {
        if (data != null) {
            Uri uri = data.getData();
            if (uri != null) {
                try {
                    String fileName = IOUtils.getDisplayName(uri);
                    if (fileName == null) {
                        fileName = "model_" + UUID.randomUUID().toString().substring(0, 8) + ".stl";
                    }
                    
                    if (!ModelSafety.isAllowedName(fileName)) {
                        Toast.makeText(getContext(), "Invalid file type: " + fileName, Toast.LENGTH_LONG).show();
                        return;
                    }

                    // Copy to cache
                    File cacheDir = getContext().getCacheDir();
                    selectedFile = new File(cacheDir, fileName);
                    try (InputStream in = getContext().getContentResolver().openInputStream(uri);
                         FileOutputStream out = new FileOutputStream(selectedFile)) {
                        byte[] buffer = new byte[8192];
                        int len;
                        while ((len = in.read(buffer)) != -1) {
                            out.write(buffer, 0, len);
                        }
                    }

                    selectedFileName = fileName;
                    pickFileBtn.setText("Selected: " + fileName);
                    publishBtn.setEnabled(true);
                    setStatus("File selected: " + fileName);
                } catch (Exception e) {
                    Toast.makeText(getContext(), "Failed to read file: " + e.getMessage(), Toast.LENGTH_LONG).show();
                }
            }
        }
    }

    private void publishModel(Context ctx) {
        String title = titleInput.getText().toString().trim();
        if (title.isEmpty()) {
            Toast.makeText(ctx, "Title is required", Toast.LENGTH_SHORT).show();
            return;
        }

        if (selectedFile == null || !selectedFile.exists()) {
            Toast.makeText(ctx, "No file selected", Toast.LENGTH_SHORT).show();
            return;
        }

        if (irohTransport == null) {
            Toast.makeText(ctx, "Iroh transport not initialized", Toast.LENGTH_SHORT).show();
            return;
        }

        String description = descriptionInput.getText().toString().trim();
        String designerName = designerNameInput.getText().toString().trim();
        String designerPubkey = designerPubkeyInput.getText().toString().trim();
        String category = categoryInput.getText().toString().trim();
        String tagsStr = tagsInput.getText().toString().trim();
        String license = licenseSpinner.getSelectedItem().toString();

        List<String> tags = new ArrayList<>();
        if (!tagsStr.isEmpty()) {
            for (String tag : tagsStr.split(",")) {
                String t = tag.trim();
                if (!t.isEmpty()) tags.add(t);
            }
        }

        ModelMetadata metadata = ModelPublisher.createMetadata(
            title, description, 
            designerName.isEmpty() ? "Anonymous" : designerName,
            designerPubkey,
            category.isEmpty() ? "uncategorized" : category,
            tags,
            license,
            ""
        );

        com.flashforge.farm.modelrepo.verify.Verdict pre =
                com.flashforge.farm.modelrepo.verify.ModelVerifier.verifyForPublish(selectedFile);
        if (!pre.allow) {
            String why = pre.detail.isEmpty() ? pre.reason.name() : pre.detail;
            Toast.makeText(ctx, "File rejected: " + why, Toast.LENGTH_LONG).show();
            setStatus("Rejected: " + why);
            return;
        }

        setStatus("Publishing...");
        publishBtn.setEnabled(false);

        new ModelPublisher(irohTransport).publishModel(selectedFile, metadata, new ModelPublisher.PublishCallback() {
            @Override
            public void onProgress(String stage, int percent) {
                setStatus(stage + " (" + percent + "%)");
            }

            @Override
            public void onPublished(String ticket, String modelJson) {
                ViewUtils.postOnMainThread(() -> {
                    setStatus("Published! Share ticket: " + ticket);
                    publishBtn.setEnabled(true);
                    Toast.makeText(ctx, "Model published successfully!", Toast.LENGTH_LONG).show();
                    
                    // Clear form
                    titleInput.setText("");
                    descriptionInput.setText("");
                    designerNameInput.setText("");
                    designerPubkeyInput.setText("");
                    categoryInput.setText("");
                    tagsInput.setText("");
                    selectedFile = null;
                    selectedFileName = null;
                    pickFileBtn.setText("Select Model File (.stl, .3mf, .obj, .step)");
                    publishBtn.setEnabled(false);
                });
            }

            @Override
            public void onError(String error) {
                ViewUtils.postOnMainThread(() -> {
                    setStatus("Publish failed: " + error);
                    publishBtn.setEnabled(true);
                    Toast.makeText(ctx, "Publish failed: " + error, Toast.LENGTH_LONG).show();
                });
            }
        });
    }

    private void setStatus(String msg) {
        if (statusView != null) {
            ViewUtils.postOnMainThread(() -> statusView.setText(msg));
        }
    }
}