package com.zanoshky.firewall

import android.content.DialogInterface
import android.net.Uri
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.materialswitch.MaterialSwitch
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * App Lock and backup/restore.
 *
 * The document picker and the share sheet background the activity, which would
 * otherwise trip the relock in [AppLock.onActivityStopped], so every launch here
 * declares itself via [AppLock.suppressNextRelock] first.
 */
class SettingsFragment : Fragment() {

    private lateinit var switchAppLock: MaterialSwitch
    private lateinit var txtLockStatus: TextView
    private lateinit var btnChangePin: TextView
    private lateinit var txtExportSummary: TextView
    private lateinit var txtBackupProgress: TextView
    private lateinit var rowExport: View
    private lateinit var rowRestore: View

    /** Guards against a second export/restore starting while one is in flight. */
    private var busy = false

    private val exportLauncher = registerForActivityResult(
        ActivityResultContracts.CreateDocument(BackupManager.MIME_TYPE)
    ) { uri -> uri?.let { runExport(it) } }

    // Backup files are commonly reported as octet-stream or plain text by file
    // managers, so the picker is not restricted to application/json.
    private val importLauncher = registerForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri -> uri?.let { confirmRestore(it) } }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        return inflater.inflate(R.layout.fragment_settings, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        switchAppLock = view.findViewById(R.id.switchAppLock)
        txtLockStatus = view.findViewById(R.id.txtLockStatus)
        btnChangePin = view.findViewById(R.id.btnChangePin)
        txtExportSummary = view.findViewById(R.id.txtExportSummary)
        txtBackupProgress = view.findViewById(R.id.txtBackupProgress)
        rowExport = view.findViewById(R.id.rowExport)
        rowRestore = view.findViewById(R.id.rowRestore)

        view.findViewById<TextView>(R.id.txtVersion).text =
            "Firewall ${BackupManager.appVersion(requireContext())}"

        switchAppLock.setOnCheckedChangeListener { _, isChecked ->
            val enabled = context?.let { AppLock.isEnabled(it) } ?: return@setOnCheckedChangeListener
            // Only react to user-driven changes; refreshLockUi() sets the state itself.
            if (isChecked == enabled) return@setOnCheckedChangeListener
            if (isChecked) showSetPinDialog(requireCurrent = false) else showRemovePinDialog()
        }

        btnChangePin.setOnClickListener { showSetPinDialog(requireCurrent = true) }

        rowExport.setOnClickListener {
            if (busy) return@setOnClickListener
            AppLock.suppressNextRelock()
            exportLauncher.launch(BackupManager.suggestedFileName())
        }

        rowRestore.setOnClickListener {
            if (busy) return@setOnClickListener
            AppLock.suppressNextRelock()
            importLauncher.launch(arrayOf("*/*"))
        }

        refreshLockUi()
        refreshExportSummary()
    }

    override fun onResume() {
        super.onResume()
        refreshLockUi()
        refreshExportSummary()
    }

    // --- App Lock ---

    private fun refreshLockUi() {
        if (!isAdded) return
        val ctx = context ?: return
        val enabled = AppLock.isEnabled(ctx)
        // Assign without firing the listener's user-change branch: the listener
        // compares against the stored value, which is already up to date here.
        switchAppLock.isChecked = enabled
        txtLockStatus.text = if (enabled) "On - PIN required to open the app" else "Off"
        btnChangePin.visibility = if (enabled) View.VISIBLE else View.GONE
    }

    private fun showSetPinDialog(requireCurrent: Boolean) {
        val ctx = context ?: return
        val body = LayoutInflater.from(ctx).inflate(R.layout.dialog_pin, null)
        val editCurrent = body.findViewById<EditText>(R.id.editPinCurrent)
        val editNew = body.findViewById<EditText>(R.id.editPinNew)
        val editConfirm = body.findViewById<EditText>(R.id.editPinConfirm)
        val txtHint = body.findViewById<TextView>(R.id.txtPinHint)
        val txtError = body.findViewById<TextView>(R.id.txtPinError)

        editCurrent.visibility = if (requireCurrent) View.VISIBLE else View.GONE
        txtHint.text = if (requireCurrent) {
            "Enter your current PIN, then choose a new one."
        } else {
            "Choose a PIN between ${AppLock.MIN_PIN_LENGTH} and ${AppLock.MAX_PIN_LENGTH} digits. " +
                "There is no recovery if you forget it - you would have to reinstall the app and lose your rules."
        }

        val dialog = MaterialAlertDialogBuilder(ctx)
            .setTitle(if (requireCurrent) "Change PIN" else "Set PIN")
            .setView(body)
            .setNegativeButton("Cancel") { _, _ -> refreshLockUi() }
            .setPositiveButton("Save", null)
            .setOnCancelListener { refreshLockUi() }
            .create()

        dialog.show()
        dialog.getButton(DialogInterface.BUTTON_POSITIVE).setOnClickListener {
            val current = editCurrent.text.toString()
            val newPin = editNew.text.toString()
            val confirm = editConfirm.text.toString()

            val formatError = AppLock.validatePinFormat(newPin)
            when {
                formatError != null -> showError(txtError, formatError)
                newPin != confirm -> showError(txtError, "The two PINs do not match")
                else -> viewLifecycleOwner.lifecycleScope.launch {
                    val c = context ?: return@launch
                    // PBKDF2 - keep it off the main thread.
                    if (requireCurrent) {
                        val ok = withContext(Dispatchers.Default) { AppLock.verify(c, current) }
                        if (!ok) {
                            showError(txtError, "Current PIN is incorrect")
                            return@launch
                        }
                    }
                    withContext(Dispatchers.Default) { AppLock.setPin(c, newPin) }
                    dialog.dismiss()
                    refreshLockUi()
                    toast(if (requireCurrent) "PIN changed" else "App Lock enabled")
                }
            }
        }
    }

    private fun showRemovePinDialog() {
        val ctx = context ?: return
        val body = LayoutInflater.from(ctx).inflate(R.layout.dialog_pin, null)
        val editCurrent = body.findViewById<EditText>(R.id.editPinCurrent)
        val txtHint = body.findViewById<TextView>(R.id.txtPinHint)
        val txtError = body.findViewById<TextView>(R.id.txtPinError)

        body.findViewById<View>(R.id.editPinNew).visibility = View.GONE
        body.findViewById<View>(R.id.editPinConfirm).visibility = View.GONE
        editCurrent.visibility = View.VISIBLE
        txtHint.text = "Enter your current PIN to turn App Lock off."

        val dialog = MaterialAlertDialogBuilder(ctx)
            .setTitle("Turn off App Lock")
            .setView(body)
            .setNegativeButton("Cancel") { _, _ -> refreshLockUi() }
            .setPositiveButton("Turn off", null)
            .setOnCancelListener { refreshLockUi() }
            .create()

        dialog.show()
        dialog.getButton(DialogInterface.BUTTON_POSITIVE).setOnClickListener {
            viewLifecycleOwner.lifecycleScope.launch {
                val c = context ?: return@launch
                val ok = withContext(Dispatchers.Default) {
                    AppLock.verify(c, editCurrent.text.toString())
                }
                if (!ok) {
                    showError(txtError, "PIN is incorrect")
                    return@launch
                }
                AppLock.clearPin(c)
                dialog.dismiss()
                refreshLockUi()
                toast("App Lock disabled")
            }
        }
    }

    private fun showError(view: TextView, message: String) {
        view.text = message
        view.visibility = View.VISIBLE
    }

    // --- Backup and restore ---

    private fun refreshExportSummary() {
        if (!isAdded) return
        viewLifecycleOwner.lifecycleScope.launch {
            val ctx = context ?: return@launch
            val ruleCount = withContext(Dispatchers.IO) {
                RuleDatabase.get(ctx).ruleDao().getAll().size
            }
            val domainCount = withContext(Dispatchers.IO) {
                BlocklistManager.getCustomDomains(ctx).size +
                    BlocklistManager.getWhitelistedDomains(ctx).size
            }
            if (!isAdded) return@launch
            txtExportSummary.text = "$ruleCount app rules, $domainCount custom domains"
        }
    }

    private fun runExport(uri: Uri) {
        viewLifecycleOwner.lifecycleScope.launch {
            val ctx = context?.applicationContext ?: return@launch
            setBusy(true, "Writing backup...")
            val result = BackupManager.export(ctx, uri)
            setBusy(false, null)
            if (!isAdded) return@launch
            result.onSuccess { s ->
                toast("Exported ${s.rules} app rules and ${s.customDomains + s.whitelistedDomains} domains")
            }
            result.onFailure { e ->
                toast("Export failed: ${e.message}")
            }
        }
    }

    private fun confirmRestore(uri: Uri) {
        val ctx = context ?: return
        MaterialAlertDialogBuilder(ctx)
            .setTitle("Restore from backup?")
            .setMessage(
                "This replaces all of your current per-app rules, custom domains and whitelist " +
                    "with the contents of the file. Apps missing from the backup end up blocked. " +
                    "Blocklists recorded in the backup are re-downloaded, which needs a connection."
            )
            .setNegativeButton("Cancel", null)
            .setPositiveButton("Restore") { _, _ -> runRestore(uri) }
            .show()
    }

    private fun runRestore(uri: Uri) {
        viewLifecycleOwner.lifecycleScope.launch {
            // Application context: a restore must finish even if this view goes away.
            val appCtx = context?.applicationContext ?: return@launch
            setBusy(true, "Restoring...")

            val result = BackupManager.restore(appCtx, uri) { status ->
                withContext(Dispatchers.Main) {
                    if (isAdded) txtBackupProgress.text = status
                }
            }

            setBusy(false, null)
            if (!isAdded) return@launch

            result.onSuccess { s ->
                val failed = if (s.sourcesFailed > 0) ", ${s.sourcesFailed} blocklist downloads failed" else ""
                toast("Restored ${s.rules} app rules and ${s.customDomains + s.whitelistedDomains} domains$failed")
                // No shared observable state exists between fragments, so recreate the
                // activity to pull every tab back in sync with the restored data.
                // The toggles carry android:saveEnabled="false" precisely so this
                // recreate cannot restore their pre-restore checked state and write
                // the stale value back over the prefs we just wrote.
                // Declare the recreate so onStop does not relock behind our back.
                AppLock.suppressNextRelock()
                activity?.recreate()
            }
            result.onFailure { e ->
                toast("Restore failed: ${e.message}")
            }
        }
    }

    private fun setBusy(value: Boolean, status: String?) {
        busy = value
        if (!isAdded) return
        rowExport.isEnabled = !value
        rowRestore.isEnabled = !value
        rowExport.alpha = if (value) 0.5f else 1f
        rowRestore.alpha = if (value) 0.5f else 1f
        txtBackupProgress.visibility = if (value) View.VISIBLE else View.GONE
        txtBackupProgress.text = status ?: ""
    }

    private fun toast(message: String) {
        context?.let { Toast.makeText(it, message, Toast.LENGTH_LONG).show() }
    }
}
