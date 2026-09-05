package constructit

import constructit.core.EvalResult
import constructit.l10n.L10n
import constructit.l10n.Messages
import constructit.l10n.Msg
import constructit.l10n.MsgError
import constructit.l10n.Msgs
import constructit.l10n.contains
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * **A message is a value** (OP-29, slice 2) — the properties the whole slice leans on.
 *
 * Everything above this file assumes four things about [Msg], and each of them is load-bearing somewhere:
 * that a message argument may itself be a message (a refusal names a face, and the *face's* name has to be
 * in the reader's language too); that a select branches inside one pattern rather than being glued together
 * from fragments; that [Msg.toString] **renders** rather than naming the key; and that a message carries no
 * language at all until something asks it for one.
 */
class MessageValueTest {
    @AfterTest
    fun resetLocale() {
        L10n.locale = "en"
    }

    /** A key and its arguments, and nothing rendered until [Msg.render] is called. */
    @Test
    fun aMessageIsAKeyAndItsArguments() {
        val msg = Msgs.wordKind(kind = "CIRCLE")
        assertEquals("word.kind", msg.key)
        assertEquals(mapOf<String, Any?>("kind" to "CIRCLE"), msg.args)
        assertEquals("a circle", msg.render("en"))
    }

    /**
     * **The argument is a message too**, and it renders in the *outer* locale — which is the whole reason
     * `FaceName.label` is a `Msg` and not a `String`: a German refusal about an English face name would be
     * half-translated, and a face name rendered when the refusal was *built* would be frozen in whatever
     * language was active then.
     */
    @Test
    fun aMessageArgumentIsRenderedInTheSameLocale() {
        val face = Msgs.nameSolidTopFace()
        val refusal = Msgs.refusalSectionPlaneDoesNotCut(label = face)
        assertEquals("the plane does not cut the top face", refusal.render("en"))
        assertTrue("Fläche" in refusal.render("de"), refusal.render("de"))
        // …and nothing about the value changed: the same object answers both
        assertEquals("the plane does not cut the top face", refusal.render("en"))
    }

    /** Nesting goes as deep as the sentence does: a name inside a reason inside a frame. */
    @Test
    fun messageArgumentsNest() {
        val inner = Msgs.refusalSectionPlaneDoesNotCut(label = Msgs.nameSolidTopFace())
        val outer = Msgs.refusalQualified(name = Msg.text("e14"), reason = inner)
        assertEquals("e14: the plane does not cut the top face", outer.render("en"))
    }

    /**
     * **An enumeration lives inside the sentence** (the ICU `select`), not beside it: the translator sees
     * the whole sentence with every branch of the choice in it, and the *a/an* that used to be picked in
     * Kotlin is inside the pattern where a language that declines can move it.
     */
    @Test
    fun anEnumerationIsASelectInsideOneSentence() {
        assertEquals("a circle", Msgs.wordKind(kind = "CIRCLE").render("en"))
        assertEquals("an arc", Msgs.wordKind(kind = "ARC").render("en"))
        assertEquals("an elliptic arc", Msgs.wordKind(kind = "ELLIPTIC_ARC").render("en"))
        assertEquals("a function curve", Msgs.wordKind(kind = "FUNC_CURVE").render("en"))
        // …and a kind the pattern has never heard of still says something
        assertEquals("an element", Msgs.wordKind(kind = "NOT_A_KIND").render("en"))
    }

    /** A plural is a plural, not an `"s"` glued on by English-only code. */
    @Test
    fun aPluralCountsInThePatternAndNotInTheCode() {
        assertEquals("1 element selected", Msgs.statusOnePickCycleElementSelected(count = 1).render("en"))
        assertEquals("3 elements selected", Msgs.statusOnePickCycleElementSelected(count = 3).render("en"))
    }

    /**
     * **[Msg.toString] renders**, in the active language — decided rather than inherited, and the class note
     * on `Msg` argues why: the alternative would put a key on the user's screen wherever a sentence still
     * quotes a message into a template.
     */
    @Test
    fun toStringRendersInTheActiveLanguage() {
        val msg = Msgs.nameSolidTopFace()
        assertEquals("the top face", "$msg")
        L10n.locale = "de"
        assertNotEquals("the top face", "$msg")
        assertEquals(Messages.nameSolidTopFace("de"), "$msg")
        L10n.locale = "en"
        assertEquals("the top face", "$msg")
    }

    /** `"…" in msg` — how every gesture test in this repository reads a refusal, present and absent. */
    @Test
    fun substringTestsReadThroughTheMessage() {
        val msg: Msg? = Msgs.nameSolidTopFace()
        assertTrue("top face" in msg)
        assertFalse("bottom face" in msg)
        val nothing: Msg? = null
        assertFalse("anything" in nothing)
    }

    /** Literal text (OP-18) is *format*, not UI: it reads the same in every language. */
    @Test
    fun literalTextIsLocaleNeutral() {
        val name = Msg.text("e14")
        assertEquals("e14", name.render("en"))
        assertEquals("e14", name.render("de"))
        assertTrue(name.isLiteral)
    }

    /** Several notes on one line: punctuation joins them, each part renders on its own. */
    @Test
    fun joinedMessagesRenderPartByPart() {
        val joined = Msg.joined(listOf(Msgs.nameSolidTopFace(), Msgs.nameSolidBottomFace()))
        assertEquals("the top face · the bottom face", joined.render("en"))
        assertEquals(Msg.EMPTY, Msg.joined(emptyList()))
        assertEquals("", Msg.EMPTY.render("de"))
    }

    /** …and a list a sentence reads out puts its final *and* in the bundle, not in a `joinToString`. */
    @Test
    fun anAndListIsAMessageToo() {
        val one = Msgs.nameSolidEdgeIndex(edge = 1)
        val two = Msgs.nameSolidEdgeIndex(edge = 2)
        val three = Msgs.nameSolidEdgeIndex(edge = 3)
        assertEquals("edge #1", Msg.andList(listOf(one)).render("en"))
        assertEquals("edge #1 and edge #2", Msg.andList(listOf(one, two)).render("en"))
        assertEquals("edge #1, edge #2 and edge #3", Msg.andList(listOf(one, two, three)).render("en"))
    }

    /**
     * **A name substituted structurally**, not textually: the shell restates the cavity's refusal in the
     * shell's own words by replacing the *argument*, which works in every language at once.
     */
    @Test
    fun aNameCanBeSubstitutedInsideAMessage() {
        val cavity = Msgs.nameSolidTopFace()
        val shell = Msgs.nameSolidInnerFaceBehindFace(face = 2)
        val about = Msgs.refusalSectionPlaneDoesNotCut(label = cavity)
        val restated = about.substituting(cavity, shell)
        assertEquals("the plane does not cut the inner face behind face #2", restated.render("en"))
        // the original is untouched — a message is a value
        assertEquals("the plane does not cut the top face", about.render("en"))
    }

    /** Two messages are equal when they say the same thing of the same things. */
    @Test
    fun messagesAreValuesAndCompareAsSuch() {
        assertEquals(Msgs.nameSolidEdgeIndex(edge = 3), Msgs.nameSolidEdgeIndex(edge = 3))
        assertNotEquals(Msgs.nameSolidEdgeIndex(edge = 3), Msgs.nameSolidEdgeIndex(edge = 4))
        assertEquals(
            Msgs.nameSolidEdgeIndex(edge = 3).hashCode(),
            Msgs.nameSolidEdgeIndex(edge = 3).hashCode(),
        )
    }

    /**
     * `EvalResult.Invalid` carries the value; `reason` is the reading of it. The engine never renders — this
     * is what keeps `commonMain` locale-free the way it is platform-free.
     */
    @Test
    fun anInvalidResultCarriesTheMessageAndRendersOnDemand() {
        val invalid = EvalResult.Invalid(Msgs.nameSolidTopFace())
        assertEquals("the top face", invalid.reason)
        L10n.locale = "de"
        assertEquals(Messages.nameSolidTopFace("de"), invalid.reason)
    }

    /**
     * …and a refusal thrown as an exception keeps its value too ([MsgError]): the evaluator unwraps it, so a
     * dimension error reads in the reader's language exactly as a returned refusal does.
     */
    @Test
    fun aThrownRefusalKeepsItsValue() {
        val thrown = MsgError(Msgs.refusalTransformSolid())
        assertEquals("a solid cannot be transformed by a 2D map (OP-17)", thrown.why.render("en"))
    }
}
