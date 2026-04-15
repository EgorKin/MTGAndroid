package io.github.romanvht.mtgandroid.ui.fragments

import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import androidx.preference.EditTextPreference
import androidx.preference.ListPreference
import androidx.preference.Preference
import androidx.preference.PreferenceFragmentCompat
import io.github.romanvht.mtgandroid.R
import io.github.romanvht.mtgandroid.utils.FormatUtils
import io.github.romanvht.mtgandroid.utils.MtgWrapper
import io.github.romanvht.mtgandroid.utils.PreferencesUtils
import io.github.romanvht.mtgandroid.utils.ValidationUtils
import io.github.romanvht.mtgandroid.utils.DebugLogStore
import io.github.romanvht.mtgandroid.utils.SecretUtils
import androidx.core.net.toUri
import io.github.romanvht.mtgandroid.BuildConfig

class SettingsFragment : PreferenceFragmentCompat() {

    override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
        setPreferencesFromResource(R.xml.preferences, rootKey)

        setupGenerateSecretButton()
        setupPreferenceSummaries()
        setupAdvancedSettingsSummaries()
        setupValidation()

        val version = MtgWrapper.GetVersion(requireContext())
        if (version != null) {
            findPreference<Preference>("mtg_version")?.summary = version
        }
        
        findPreference<Preference>("app_version")?.summary = BuildConfig.VERSION_NAME

        findPreference<Preference>("github_link")?.setOnPreferenceClickListener {
            val intent = Intent(Intent.ACTION_VIEW).apply {
                data = "https://github.com/romanvht/MTGAndroid".toUri()
            }
            startActivity(intent)
            true
        }
    }

    private fun setupGenerateSecretButton() {
        findPreference<Preference>("generate_secret")?.setOnPreferenceClickListener {
            val domain = PreferencesUtils.getDomain(requireContext())

            if (!ValidationUtils.isValidDomain(domain)) {
                Toast.makeText(
                    requireContext(),
                    R.string.error_empty_domain,
                    Toast.LENGTH_SHORT
                ).show()
                return@setOnPreferenceClickListener true
            }

            val cleanDomain = FormatUtils.cleanDomain(domain)
            val transportMode = PreferencesUtils.getTransportMode(requireContext())
            val secret = if (transportMode == "websocket") {
                DebugLogStore.i("SettingsFragment", "Generating secret via SecretUtils for websocket mode")
                SecretUtils.generateFakeTlsSecret(cleanDomain)
            } else {
                DebugLogStore.i("SettingsFragment", "Generating secret via native mtg for legacy mode")
                MtgWrapper.generateSecret(requireContext(), cleanDomain)
            }

            if (secret != null && ValidationUtils.isValidSecretForMode(secret, transportMode)) {
                PreferencesUtils.setSecret(requireContext(), secret)

                findPreference<EditTextPreference>("secret")?.text = secret

                Toast.makeText(
                    requireContext(),
                    R.string.secret_generated,
                    Toast.LENGTH_SHORT
                ).show()
            } else {
                DebugLogStore.e("SettingsFragment", "Secret generation failed. Check mtg binary availability")
                Toast.makeText(
                    requireContext(),
                    R.string.error_generate_secret,
                    Toast.LENGTH_SHORT
                ).show()
            }

            true
        }
    }

    private fun setupPreferenceSummaries() {
        findPreference<EditTextPreference>("domain")?.apply {
            summaryProvider = EditTextPreference.SimpleSummaryProvider.getInstance()
            setOnPreferenceChangeListener { _, newValue ->
                ValidationUtils.isValidDomain(newValue as String).also { isValid ->
                    if (!isValid) {
                        Toast.makeText(
                            requireContext(),
                            R.string.error_domain_format,
                            Toast.LENGTH_SHORT
                        ).show()
                    } else {
                        generateSecretForDomain(FormatUtils.cleanDomain(newValue))
                    }
                }
            }
        }

        findPreference<EditTextPreference>("ip_address")?.apply {
            summaryProvider = EditTextPreference.SimpleSummaryProvider.getInstance()
            setOnPreferenceChangeListener { _, newValue ->
                ValidationUtils.isValidIpAddress(newValue as String).also { isValid ->
                    if (!isValid) {
                        Toast.makeText(
                            requireContext(),
                            R.string.error_invalid_ip,
                            Toast.LENGTH_SHORT
                        ).show()
                    }
                }
            }
        }

        findPreference<EditTextPreference>("port")?.apply {
            summaryProvider = EditTextPreference.SimpleSummaryProvider.getInstance()
            setOnBindEditTextListener { editText ->
                editText.inputType = android.text.InputType.TYPE_CLASS_NUMBER
            }
            setOnPreferenceChangeListener { _, newValue ->
                val normalizedPort = FormatUtils.normalizePort(newValue as String)

                when {
                    !ValidationUtils.isValidPort(normalizedPort) -> {
                        Toast.makeText(
                            requireContext(),
                            R.string.error_invalid_port,
                            Toast.LENGTH_SHORT
                        ).show()
                        false
                    }
                    !ValidationUtils.isNonPrivilegedPort(normalizedPort) -> {
                        Toast.makeText(
                            requireContext(),
                            R.string.warning_privileged_port,
                            Toast.LENGTH_LONG
                        ).show()
                        true
                    }
                    else -> true
                }
            }
        }

        findPreference<EditTextPreference>("secret")?.apply {
            setOnBindEditTextListener { editText ->
                editText.isSingleLine = false
                editText.setLines(3)
            }
            setSummaryProvider { preference ->
                val secret = (preference as EditTextPreference).text
                secret?.ifEmpty { getString(R.string.error_empty_secret) }
                    ?: getString(R.string.error_empty_secret)
            }
            setOnPreferenceChangeListener { _, newValue ->
                val secret = (newValue as String).trim()
                val transportMode = PreferencesUtils.getTransportMode(requireContext())
                val isValid = ValidationUtils.isValidSecretForMode(secret, transportMode)
                if (!isValid) {
                    Toast.makeText(
                        requireContext(),
                        R.string.error_invalid_secret,
                        Toast.LENGTH_SHORT
                    ).show()
                }
                isValid
            }
        }
    }

    private fun setupAdvancedSettingsSummaries() {
        findPreference<EditTextPreference>("concurrency")?.apply {
            summaryProvider = EditTextPreference.SimpleSummaryProvider.getInstance()
            setOnBindEditTextListener { editText ->
                editText.inputType = android.text.InputType.TYPE_CLASS_NUMBER
            }
            setOnPreferenceChangeListener { _, newValue ->
                ValidationUtils.isValidConcurrency(newValue as String).also { isValid ->
                    if (!isValid) {
                        Toast.makeText(
                            requireContext(),
                            R.string.error_invalid_concurrency,
                            Toast.LENGTH_SHORT
                        ).show()
                    }
                }
            }
        }

        findPreference<EditTextPreference>("doh_ip")?.apply {
            summaryProvider = EditTextPreference.SimpleSummaryProvider.getInstance()
            setOnPreferenceChangeListener { _, newValue ->
                ValidationUtils.isValidIpAddress(newValue as String).also { isValid ->
                    if (!isValid) {
                        Toast.makeText(
                            requireContext(),
                            R.string.error_invalid_ip,
                            Toast.LENGTH_SHORT
                        ).show()
                    }
                }
            }
        }

        findPreference<ListPreference>("transport_mode")?.apply {
            summaryProvider = ListPreference.SimpleSummaryProvider.getInstance()
        }

        findPreference<EditTextPreference>("ws_template")?.apply {
            summaryProvider = EditTextPreference.SimpleSummaryProvider.getInstance()
            setOnPreferenceChangeListener { _, newValue ->
                val value = (newValue as String).trim()
                val isValid = value.startsWith("wss://") && value.contains("/apiws")
                if (!isValid) {
                    Toast.makeText(
                        requireContext(),
                        R.string.error_invalid_ws_template,
                        Toast.LENGTH_SHORT
                    ).show()
                }
                isValid
            }
        }

        findPreference<EditTextPreference>("timeout")?.apply {
            summaryProvider = EditTextPreference.SimpleSummaryProvider.getInstance()
            setOnBindEditTextListener { editText ->
                editText.inputType = android.text.InputType.TYPE_CLASS_NUMBER
            }
            setOnPreferenceChangeListener { _, newValue ->
                ValidationUtils.isValidTimeout(newValue as String).also { isValid ->
                    if (!isValid) {
                        Toast.makeText(
                            requireContext(),
                            R.string.error_invalid_timeout,
                            Toast.LENGTH_SHORT
                        ).show()
                    }
                }
            }
        }

        findPreference<EditTextPreference>("antireplay_cache")?.apply {
            summaryProvider = EditTextPreference.SimpleSummaryProvider.getInstance()
            setOnBindEditTextListener { editText ->
                editText.inputType = android.text.InputType.TYPE_CLASS_NUMBER
            }
            setOnPreferenceChangeListener { _, newValue ->
                ValidationUtils.isValidAntiReplayCache(newValue as String).also { isValid ->
                    if (!isValid) {
                        Toast.makeText(
                            requireContext(),
                            R.string.error_invalid_antireplay_cache,
                            Toast.LENGTH_SHORT
                        ).show()
                    }
                }
            }
        }
    }

    private fun generateSecretForDomain(cleanDomain: String) {
        val transportMode = PreferencesUtils.getTransportMode(requireContext())
        val secret = if (transportMode == "websocket") {
            DebugLogStore.i("SettingsFragment", "Auto-generating secret via SecretUtils for websocket mode")
            SecretUtils.generateFakeTlsSecret(cleanDomain)
        } else {
            DebugLogStore.i("SettingsFragment", "Auto-generating secret via native mtg for legacy mode")
            MtgWrapper.generateSecret(requireContext(), cleanDomain)
        }

        if (secret != null && ValidationUtils.isValidSecretForMode(secret, transportMode)) {
            PreferencesUtils.setSecret(requireContext(), secret)
            findPreference<EditTextPreference>("secret")?.text = secret

            Toast.makeText(
                requireContext(),
                R.string.secret_generated,
                Toast.LENGTH_SHORT
            ).show()
        } else {
            DebugLogStore.e("SettingsFragment", "Auto secret generation failed for domain=$cleanDomain")
            Toast.makeText(
                requireContext(),
                R.string.error_generate_secret,
                Toast.LENGTH_SHORT
            ).show()
        }
    }

    private fun setupValidation() {
        preferenceScreen.sharedPreferences?.registerOnSharedPreferenceChangeListener { _, key ->
            when (key) {
                "domain" -> {
                    val domain = PreferencesUtils.getDomain(requireContext())
                    if (domain.isNotEmpty()) {
                        PreferencesUtils.setDomain(
                            requireContext(),
                            FormatUtils.cleanDomain(domain)
                        )
                    }
                }
                "ip_address" -> {
                    val ip = PreferencesUtils.getIpAddress(requireContext())
                    PreferencesUtils.setIpAddress(
                        requireContext(),
                        FormatUtils.normalizeIpAddress(ip)
                    )
                }
                "port" -> {
                    val port = PreferencesUtils.getPort(requireContext())
                    PreferencesUtils.setPort(
                        requireContext(),
                        FormatUtils.normalizePort(port)
                    )
                }
            }
        }
    }
}
