package constructit.l10n

import com.ibm.icu.text.MessageFormat
import com.ibm.icu.util.ULocale

/**
 * ICU4J (OP-29). The JVM half of the [formatMessage] seam, and the reason there is no message parser in
 * this repository: `com.ibm.icu.text.MessageFormat` is the reference implementation of the very syntax the
 * ARB files are written in, plurals, selects and number skeletons included.
 *
 * Its 13 MB never reach the browser — this is `jvmMain`, so the tests and any JVM tool get the full engine
 * and the bundle gets FormatJS's 40 KB instead.
 */
public actual fun formatMessage(
    locale: String,
    pattern: String,
    args: Map<String, Any?>,
): String = MessageFormat(pattern, ULocale.forLanguageTag(locale.replace('_', '-'))).format(args)
