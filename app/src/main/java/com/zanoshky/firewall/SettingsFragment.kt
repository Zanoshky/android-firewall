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
    private lateinit var btnChangePasscode: TextView
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
        btnChangePasscode = view.findViewById(R.id.btnChangePin)
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
            if (isChecked) showSetPasscodeDialog(requireCurrent = false) else showRemovePasscodeDialog()
        }

        btnChangePasscode.setOnClickListener { showSetPasscodeDialog(requireCurrent = true) }

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
        txtLockStatus.text = if (enabled) "On, a passcode is needed to open the app" else "Off"
        btnChangePasscode.visibility = if (enabled) View.VISIBLE else View.GONE
    }

    private fun showSetPasscodeDialog(requireCurrent: Boolean) {
        val ctx = context ?: return
        val body = LayoutInflater.from(ctx).inflate(R.layout.dialog_pin, null)
        val editCurrent = body.findViewById<EditText>(R.id.editPinCurrent)
        val editNew = body.findViewById<EditText>(R.id.editPinNew)
        val editConfirm = body.findViewById<EditText>(R.id.editPinConfirm)
        val txtHint = body.findViewById<TextView>(R.id.txtPinHint)
        val txtError = body.findViewById<TextView>(R.id.txtPinError)

        editCurrent.visibility = if (requireCurrent) View.VISIBLE else View.GONE
        txtHint.text = if (requireCurrent) {
            "Enter your current passcode, then choose a new one."
        } else {
            "Letters, digits and symbols, at least ${AppLock.MIN_LENGTH} characters. " +
                "There is no way to recover it: forget it and the app has to be reinstalled, " +
                "which loses your rules."
        }

        val dialog = MaterialAlertDialogBuilder(ctx)
            .setTitle(if (requireCurrent) "Change passcode" else "Set a passcode")
            .setView(body)
            .setNegativeButton("Cancel") { _, _ -> refreshLockUi() }
            .setPositiveButton("Save", null)
            .setOnCancelListener { refreshLockUi() }
            .create()

        dialog.show()
        dialog.getButton(DialogInterface.BUTTON_POSITIVE).setOnClickListener {
            val current = editCurrent.text.toString()
            val newPasscode = editNew.text.toString()
            val confirm = editConfirm.text.toString()

            val formatError = AppLock.validateFormat(newPasscode)
            when {
                formatError != null -> showError(txtError, formatError)
                newPasscode != confirm -> showError(txtError, "The two passcodes do not match")
                else -> viewLifecycleOwner.lifecycleScope.launch {
                    val c = context ?: return@launch
                    // PBKDF2 - keep it off the main thread.
                    if (requireCurrent) {
                        val ok = withContext(Dispatchers.Default) { AppLock.verify(c, current) }
                        if (!ok) {
                            showError(txtError, "That is not your current passcode")
                            return@launch
                        }
                    }
                    withContext(Dispatchers.Default) { AppLock.setPasscode(c, newPasscode) }
                    dialog.dismiss()
                    refreshLockUi()
                    toast(if (requireCurrent) "Passcode changed" else "App Lock is on")
                }
            }
        }
    }

    private fun showRemovePasscodeDialog() {
        val ctx = context ?: return
        val body = LayoutInflater.from(ctx).inflate(R.layout.dialog_pin, null)
        val editCurrent = body.findViewById<EditText>(R.id.editPinCurrent)
        val txtHint = body.findViewById<TextView>(R.id.txtPinHint)
        val txtError = body.findViewById<TextView>(R.id.txtPinError)

        body.findViewById<View>(R.id.editPinNew).visibility = View.GONE
        body.findViewById<View>(R.id.editPinConfirm).visibility = View.GONE
        editCurrent.visibility = View.VISIBLE
        txtHint.text = "Enter your current passcode to turn App Lock off."

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
                    showError(txtError, "That is not your passcode")
                    return@launch
                }
                AppLock.clearPasscode(c)
                dialog.dismiss()
                refreshLockUi()
                toast("App Lock is off")
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
            val domainCount = DomainRules.blockedCount() + DomainRules.allowedCount()
            if (!isAdded) return@launch
            txtExportSummary.text = "$ruleCount app rules, $domainCount domain rules"
        }
    }

    private fun runExport(uri: Uri) {
        viewLifecycleOwner.lifecycleScope.launch {
            val ctx = context?.applicationContext ?: return@launch
            setBusy(true, "Writing backup...")
            val result = BackupManager.export(ctx, uri)
            setBusy(false, null)
            if (!isAdded) return@launch
            result.onSuccess { summary ->
                toast("Exported ${summary.rules} app rules and ${summary.domains} domain rules")
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
                "This replaces your app modes and your domain rules with what is in the file. " +
                    "Apps the backup does not mention end up blocked. Tracker lists named in it are " +
                    "downloaded again, which needs a connection."
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

            result.onSuccess { summary ->
                val failed = if (summary.sourcesFailed > 0) {
                    ", ${summary.sourcesFailed} list downloads failed"
                } else ""
                toast("Restored ${summary.rules} app rules and ${summary.domains} domain rules$failed")
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
