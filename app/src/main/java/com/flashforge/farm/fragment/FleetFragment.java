package com.flashforge.farm.fragment;

import android.content.Context;
import android.app.Activity;
import android.content.Intent;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.text.InputType;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Spinner;
import android.widget.ArrayAdapter;
import android.widget.TextView;
import android.widget.AdapterView;
import android.app.AlertDialog;
import com.flashforge.farm.iroh.IrohP2PService;

import androidx.core.widget.NestedScrollView;

import java.util.List;

import com.flashforge.farm.MainActivity;
import com.flashforge.farm.R;
import com.flashforge.farm.components.FarmAlertDialogBuilder;
import com.flashforge.farm.navigation.Fragment;
import com.flashforge.farm.theme.ThemesRepo;
import com.flashforge.farm.utils.PrinterFleetManager;
import com.flashforge.farm.utils.PrintQueueManager;
import com.flashforge.farm.utils.UsbProvisioningManager;
import com.flashforge.farm.utils.ViewUtils;
import com.flashforge.farm.Bus;
import com.flashforge.farm.events.NeedSnackbarEvent;

public class FleetFragment extends Fragment {
    private LinearLayout contentLayout;
    private NestedScrollView scrollView;

    /** Pending USB-provision request, driven by the Fleet UI and consumed by MainActivity result. */
    private static String pendingPrinterId;
    private static int pendingMode = 0;
    private static String pendingSaveTarget = "USB"; // "USB" | "FILE" | "SHARE"

    @Override
    public View onCreateView(Context ctx) {
        FrameLayout root = new FrameLayout(ctx);
        scrollView = new NestedScrollView(ctx);
        contentLayout = new LinearLayout(ctx);
        contentLayout.setOrientation(LinearLayout.VERTICAL);
        contentLayout.setPadding(ViewUtils.dp(16), ViewUtils.dp(16), ViewUtils.dp(16), ViewUtils.dp(16));

        scrollView.addView(contentLayout, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        root.addView(scrollView, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        refreshView();

        return root;
    }

    private void refreshView() {
        if (contentLayout == null) return;
        contentLayout.removeAllViews();

        Context ctx = getContext();

        // Printers Section
        TextView printersTitle = new TextView(ctx);
        printersTitle.setText(R.string.SlotFleet);
        printersTitle.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 24);
        printersTitle.setTypeface(ViewUtils.getTypeface(ViewUtils.ROBOTO_MEDIUM));
        printersTitle.setTextColor(ThemesRepo.getColor(android.R.attr.textColorPrimary));
        contentLayout.addView(printersTitle, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        List<PrinterFleetManager.Printer> printers = PrinterFleetManager.getPrinters();
        for (PrinterFleetManager.Printer p : printers) {
            TextView pt = new TextView(ctx);
            String code = p.accessCode != null ? p.accessCode : "—";
            pt.setText(p.name + " (" + p.ipOrUrl + ")\n" + getString(R.string.FleetUniqueCode) + ": " + code + "\n"
                    + p.loadedFilamentColor + " " + p.loadedFilamentType + " @ " + p.nozzleSize + "mm");
            pt.setTextColor(ThemesRepo.getColor(android.R.attr.textColorPrimary));
            pt.setPadding(0, ViewUtils.dp(8), 0, ViewUtils.dp(8));
            contentLayout.addView(pt);

            // Save-target dropdown: USB (default) | File folder | Share
            Spinner saveTargetSpinner = new Spinner(ctx);
            ArrayAdapter<CharSequence> adapter = ArrayAdapter.createFromResource(ctx,
                    R.array.FleetSaveTargets, android.R.layout.simple_spinner_item);
            adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
            saveTargetSpinner.setAdapter(adapter);
            saveTargetSpinner.setSelection(0); // default USB
            saveTargetSpinner.setLayoutParams(new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
            saveTargetSpinner.setPrompt(R.string.FleetSaveTarget);
            contentLayout.addView(saveTargetSpinner);

            Button provisionBtn = new Button(ctx);
            provisionBtn.setText(R.string.FleetProvisionViaUsb);
            provisionBtn.setOnClickListener(v -> promptProvision(p, true, saveTargetSpinner));
            contentLayout.addView(provisionBtn, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

            Button p2pBtn = new Button(ctx);
            p2pBtn.setText(R.string.FleetProvisionConnect);
            p2pBtn.setOnClickListener(v -> connectViaP2P(p));
            contentLayout.addView(p2pBtn, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        }

        Button addPrinterBtn = new Button(ctx);
        addPrinterBtn.setText(R.string.FleetAddPrinter);
        addPrinterBtn.setOnClickListener(v -> showAddPrinterDialog());
        contentLayout.addView(addPrinterBtn, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        // Queue Section
        TextView queueTitle = new TextView(ctx);
        queueTitle.setText("Print Queue");
        queueTitle.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 20);
        queueTitle.setTypeface(ViewUtils.getTypeface(ViewUtils.ROBOTO_MEDIUM));
        queueTitle.setTextColor(ThemesRepo.getColor(android.R.attr.textColorPrimary));
        queueTitle.setPadding(0, ViewUtils.dp(24), 0, 0);
        contentLayout.addView(queueTitle, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        List<PrintQueueManager.QueueItem> queue = PrintQueueManager.getQueue();
        if (queue.isEmpty()) {
            TextView empty = new TextView(ctx);
            empty.setText("Queue is empty");
            empty.setTextColor(ThemesRepo.getColor(android.R.attr.textColorSecondary));
            contentLayout.addView(empty);
        } else {
            for (PrintQueueManager.QueueItem item : queue) {
                TextView qt = new TextView(ctx);
                String priorityStr = item.priority == 2 ? "High" : (item.priority == 0 ? "Low" : "Normal");
                qt.setText("Priority: " + priorityStr + " | Status: " + item.status + "\nRequires: " + item.requiredFilamentColor + " " + item.requiredFilamentType + " @ " + item.requiredNozzleSize + "mm");
                qt.setTextColor(ThemesRepo.getColor(android.R.attr.textColorPrimary));
                qt.setPadding(0, ViewUtils.dp(8), 0, ViewUtils.dp(8));
                qt.setOnClickListener(v -> showEditQueueDialog(item));
                contentLayout.addView(qt);
            }
        }
    }


    private void showEditQueueDialog(PrintQueueManager.QueueItem item) {
        Context ctx = getContext();
        AlertDialog.Builder builder = new AlertDialog.Builder(ctx);
        builder.setTitle("Edit Job");

        LinearLayout layout = new LinearLayout(ctx);
        layout.setOrientation(LinearLayout.VERTICAL);
        layout.setPadding(ViewUtils.dp(20), ViewUtils.dp(20), ViewUtils.dp(20), ViewUtils.dp(20));

        final EditText priorityInput = new EditText(ctx);
        priorityInput.setHint("Priority (0=Low, 1=Normal, 2=High)");
        priorityInput.setText(String.valueOf(item.priority));
        priorityInput.setInputType(InputType.TYPE_CLASS_NUMBER);
        layout.addView(priorityInput);

        final EditText nozzleInput = new EditText(ctx);
        nozzleInput.setHint(R.string.FleetNozzleSize);
        nozzleInput.setText(item.requiredNozzleSize);
        layout.addView(nozzleInput);

        final EditText typeInput = new EditText(ctx);
        typeInput.setHint(R.string.FleetFilamentType);
        typeInput.setText(item.requiredFilamentType);
        layout.addView(typeInput);

        final EditText colorInput = new EditText(ctx);
        colorInput.setHint(R.string.FleetFilamentColor);
        colorInput.setText(item.requiredFilamentColor);
        layout.addView(colorInput);

        builder.setView(layout);

        builder.setPositiveButton("Save", (dialog, which) -> {
            try {
                item.priority = Integer.parseInt(priorityInput.getText().toString());
            } catch (NumberFormatException ignored) {}
            item.requiredNozzleSize = nozzleInput.getText().toString();
            item.requiredFilamentType = typeInput.getText().toString();
            item.requiredFilamentColor = colorInput.getText().toString();

            List<PrintQueueManager.QueueItem> queue = PrintQueueManager.getQueue();
            for (PrintQueueManager.QueueItem qi : queue) {
                if (qi.id.equals(item.id)) {
                    qi.priority = item.priority;
                    qi.requiredNozzleSize = item.requiredNozzleSize;
                    qi.requiredFilamentType = item.requiredFilamentType;
                    qi.requiredFilamentColor = item.requiredFilamentColor;
                    break;
                }
            }
            PrintQueueManager.saveQueue(queue);
            refreshView();
        });
        builder.setNegativeButton("Cancel", (dialog, which) -> dialog.cancel());
        builder.setNeutralButton("Delete", (dialog, which) -> {
            List<PrintQueueManager.QueueItem> queue = PrintQueueManager.getQueue();
            for (int i = 0; i < queue.size(); i++) {
                if (queue.get(i).id.equals(item.id)) {
                    queue.remove(i);
                    break;
                }
            }
            PrintQueueManager.saveQueue(queue);
            refreshView();
        });

        builder.show();
    }

    private void showAddPrinterDialog() {
        Context ctx = getContext();
        AlertDialog.Builder builder = new AlertDialog.Builder(ctx);
        builder.setTitle(R.string.FleetAddPrinter);

        LinearLayout layout = new LinearLayout(ctx);
        layout.setOrientation(LinearLayout.VERTICAL);
        layout.setPadding(ViewUtils.dp(20), ViewUtils.dp(20), ViewUtils.dp(20), ViewUtils.dp(20));

        final EditText nameInput = new EditText(ctx);
        nameInput.setHint(R.string.FleetPrinterName);
        layout.addView(nameInput);

        android.widget.CheckBox p2pCheck = new android.widget.CheckBox(ctx);
        p2pCheck.setText("Enable P2P Mode");
        layout.addView(p2pCheck);

        final EditText nozzleInput = new EditText(ctx);
        nozzleInput.setHint(R.string.FleetNozzleSize);
        nozzleInput.setText("0.4");
        layout.addView(nozzleInput);

        final EditText typeInput = new EditText(ctx);
        typeInput.setHint(R.string.FleetFilamentType);
        typeInput.setText("PLA");
        layout.addView(typeInput);

        final EditText colorInput = new EditText(ctx);
        colorInput.setHint(R.string.FleetFilamentColor);
        layout.addView(colorInput);

        TextView note = new TextView(ctx);
        note.setText(R.string.FleetLANModeNote);
        note.setTextColor(ThemesRepo.getColor(android.R.attr.textColorSecondary));
        layout.addView(note);

        builder.setView(layout);

        builder.setPositiveButton("Add", (dialog, which) -> {
            PrinterFleetManager.Printer p = new PrinterFleetManager.Printer(
                String.valueOf(System.currentTimeMillis()),
                "",
                nameInput.getText().toString().trim(),
                nozzleInput.getText().toString().trim(),
                typeInput.getText().toString().trim(),
                colorInput.getText().toString().trim()
            );
            PrinterFleetManager.addPrinter(p);
            refreshView();
        });
        builder.setNegativeButton("Cancel", (dialog, which) -> dialog.cancel());

        builder.show();
    }

    // ---- P2P / USB provisioning ----

    /** Deploy or remove the p2p module. The {@code saveTargetSpinner} selects the save
     *  destination: "USB" (default) writes to an auto-detected USB drive, "File" opens the
     *  SAF folder picker, and "Share" opens the system share sheet. */
    private void promptProvision(PrinterFleetManager.Printer printer, boolean install, Spinner saveTargetSpinner) {
        Context ctx = getContext();
        if (!(ctx instanceof Activity)) return;

        final int selectedTargetPos = saveTargetSpinner != null ? saveTargetSpinner.getSelectedItemPosition() : 0;
        final String saveTarget;
        switch (selectedTargetPos) {
            case 1: saveTarget = "FILE"; break;
            case 2: saveTarget = "SHARE"; break;
            default: saveTarget = "USB"; break;
        }

        if (!install || "USB".equals(saveTarget)) {
            // USB / uninstall: skip the install-mode picker for the uninstall case, but
            // for the install case we still want to ask RAM vs INSTALL.
            if (install) {
                promptInstallModeThenSave(printer, saveTarget);
            } else {
                // Uninstall path: no mode picker needed.
                pendingPrinterId = printer.id;
                pendingMode = 0;
                pendingSaveTarget = saveTarget;
                launchSaveTargetPicker(printer, false, saveTarget);
            }
            return;
        }

        // File / Share targets: still ask RAM vs INSTALL
        promptInstallModeThenSave(printer, saveTarget);
    }

    /** Modal: choose RAM vs INSTALL, then proceed to the chosen save target. */
    private void promptInstallModeThenSave(PrinterFleetManager.Printer printer, String saveTarget) {
        Context ctx = getContext();
        if (ctx == null) return;
        final int[] selectedMode = { 0 };
        AlertDialog.Builder b = new AlertDialog.Builder(ctx);
        b.setTitle(R.string.FleetProvisionMode);
        b.setSingleChoiceItems(new CharSequence[]{
                getString(R.string.FleetProvisionModeRam),
                getString(R.string.FleetProvisionModeInstall)
        }, selectedMode[0], (d, which) -> selectedMode[0] = which);
        b.setPositiveButton(android.R.string.ok, (d, which) -> {
            pendingPrinterId = printer.id;
            pendingMode = selectedMode[0];
            pendingSaveTarget = saveTarget;
            launchSaveTargetPicker(printer, true, saveTarget);
        });
        b.setNegativeButton(android.R.string.cancel, null);
        b.show();
    }

    /** Dispatch the deployment flow based on the save target. */
    private void launchSaveTargetPicker(PrinterFleetManager.Printer printer, boolean install, String saveTarget) {
        Context ctx = getContext();
        if (!(ctx instanceof Activity)) return;
        Activity act = (Activity) ctx;

        if ("USB".equals(saveTarget)) {
            // Try auto-detect a connected USB drive. If exactly one is found, write to it
            // immediately. If zero are found, fall through to the SAF picker. If more than
            // one, the user picks via the SAF picker.
            UsbProvisioningManager.UsbAutoDetect auto = UsbProvisioningManager.autoDetectUsb(ctx);
            if (auto != null && auto.treeUri != null) {
                // Direct write to the detected USB root — no picker.
                if (install) {
                    UsbProvisioningManager.provision(ctx, auto.treeUri, null, pendingMode,
                            layoutPrinterOrDefault(printer), this::onProvisionCallback);
                } else {
                    UsbProvisioningManager.uninstall(ctx, auto.treeUri, this::onProvisionCallback);
                }
                return;
            }
            if (auto != null && auto.multiple) {
                // Multiple drives: fall through to picker to disambiguate.
            } else {
// No drive detected. Inform the user; offer to fall back to the file picker.
                new FarmAlertDialogBuilder(ctx)
                        .setTitle(R.string.FleetProvisionViaUsb)
                        .setMessage(R.string.FleetNoUsbDetected)
                        .setPositiveButton(android.R.string.ok, (d, w) -> {
                            pendingSaveTarget = "FILE";
                            act.startActivityForResult(UsbProvisioningManager.buildUsbPickerIntent(),
                                    install ? MainActivity.REQUEST_CODE_PROVISION_USB : MainActivity.REQUEST_CODE_UNINSTALL_USB);
                        })
                        .setNegativeButton(android.R.string.cancel, null)
                        .show();
                return;
            }
        } else if ("SHARE".equals(saveTarget)) {
            // Build the deployment package into the app's cache, then share it.
            UsbProvisioningManager.buildSharePackage(ctx, install, pendingMode,
                    layoutPrinterOrDefault(printer), (ok, msg, file) -> {
                        if (!ok || file == null) {
                            onProvisionCallback(false, msg != null ? msg : "share package failed");
                            return;
                        }
                        android.net.Uri shareUri = androidx.core.content.FileProvider.getUriForFile(
                                ctx, ctx.getPackageName() + ".provider", file);
                        Intent share = new Intent(Intent.ACTION_SEND);
                        share.setType("application/octet-stream");
                        share.putExtra(Intent.EXTRA_STREAM, shareUri);
                        share.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
                        Intent chooser = Intent.createChooser(share, getString(R.string.FleetProvisionViaUsb));
                        act.startActivity(chooser);
                        onProvisionCallback(true, "Share sheet opened.");
                    });
            return;
        }

        // "FILE" (or USB fallback): launch the SAF tree picker.
        act.startActivityForResult(UsbProvisioningManager.buildUsbPickerIntent(),
                install ? MainActivity.REQUEST_CODE_PROVISION_USB : MainActivity.REQUEST_CODE_UNINSTALL_USB);
    }

    /** Callback for both USB-direct and share-target provisioning completions. */
    private void onProvisionCallback(boolean ok, String msg) {
        postSnack(ok ? R.string.FleetProvisioningDone : R.string.FleetProvisionViaUsb, msg);
        if (getContext() != null) {
            new Handler(Looper.getMainLooper()).post(() -> {
                if (getView() != null) refreshView();
            });
        }
    }

    /** Connect to a provisioned printer over the p2p overlay by its unique code. */
    private void connectViaP2P(PrinterFleetManager.Printer printer) {
        Context ctx = getContext();
        if (ctx == null || printer == null) return;
        IrohP2PService.dial(ctx, printer.accessCode, printer.ipOrUrl, new IrohP2PService.DialCallback() {
            @Override
            public void onResult(boolean ok, String msg) {
                if (!ok) {
                    new FarmAlertDialogBuilder(ctx)
                        .setTitle(R.string.FleetProvisionConnect)
                        .setMessage(getString(R.string.FleetP2pNotReady) + "\n\n" + msg)
                        .setPositiveButton(android.R.string.ok, null)
                        .show();
                }
            }
        });
    }

    /** Called by MainActivity after the SAF tree picker returns. Performs the USB deploy. */
    public void onUsbDeployResult(Uri treeUri, boolean install) {
        final int mode = pendingMode;
        final String printerId = pendingPrinterId;
        final String saveTarget = pendingSaveTarget;
        pendingPrinterId = null;
        pendingMode = 0;
        pendingSaveTarget = "USB";
        if (treeUri == null) return;

        Context ctx = getContext();
        PrinterFleetManager.Printer printer = printerId != null ? PrinterFleetManager.findPrinter(printerId) : null;
        if (install) {
            UsbProvisioningManager.provision(ctx, treeUri, null, mode, layoutPrinterOrDefault(printer), this::onProvisionCallback);
        } else {
            UsbProvisioningManager.uninstall(ctx, treeUri, this::onProvisionCallback);
        }
    }

    private PrinterFleetManager.Printer layoutPrinterOrDefault(PrinterFleetManager.Printer p) {
        if (p != null) return p;
        // No stored printer yet — create a throwaway identity so provisioning still works.
        return new PrinterFleetManager.Printer("tmp", "", "", "", "", "");
    }

    private void postSnack(int fallbackRes, String message) {
        if (message == null) {
            Bus.NEED_SNACKBAR.postValue(new NeedSnackbarEvent(fallbackRes));
        } else {
            Bus.NEED_SNACKBAR.postValue(new NeedSnackbarEvent(message));
        }
    }

    private String getString(int res) {
        return getContext() == null ? "" : getContext().getString(res);
    }

    @Override
    public void onApplyTheme() {
        if (scrollView != null) {
            scrollView.setBackgroundColor(ThemesRepo.getColor(android.R.attr.windowBackground));
            refreshView();
        }
    }
}
