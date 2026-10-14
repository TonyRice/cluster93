package cluster93.core.job;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** In-script policy declarations (docs/design/orphaned-jobs.md). */
class ScriptConfigTest {

    @Test
    void emptyAndBlankScriptsDeclareNothing() {
        assertThat(ScriptConfig.parse(null)).isEqualTo(ScriptConfig.EMPTY);
        assertThat(ScriptConfig.parse("")).isEqualTo(ScriptConfig.EMPTY);
        assertThat(ScriptConfig.parse("   \n\t ")).isEqualTo(ScriptConfig.EMPTY);
    }

    @Test
    void aScriptWithoutDeclarationsDeclaresNothing() {
        assertThat(ScriptConfig.parse("export default () => 1")).isEqualTo(ScriptConfig.EMPTY);
        assertThat(ScriptConfig.parse("const onOrphan = 'retry'; export default () => onOrphan")).isEqualTo(ScriptConfig.EMPTY);
    }

    @Test
    void readsAConfigObjectWithSingleQuotes() {
        ScriptConfig config = ScriptConfig.parse("""
                export const config = { onOrphan: 'retry', maxAttempts: 3, timeoutMs: 30000 };
                export default () => 1;
                """);

        assertThat(config.onOrphan()).isEqualTo(OrphanPolicy.RETRY);
        assertThat(config.maxAttempts()).isEqualTo(3);
        assertThat(config.timeoutMs()).isEqualTo(30_000L);
    }

    @Test
    void readsAConfigObjectWithDoubleQuotesAndVarOrLet() {
        assertThat(ScriptConfig.parse("export var config = { onOrphan: \"resume\" };").onOrphan()).isEqualTo(OrphanPolicy.RESUME);
        assertThat(ScriptConfig.parse("export let config = { onOrphan: \"fail\" };").onOrphan()).isEqualTo(OrphanPolicy.FAIL);
    }

    @Test
    void readsAConfigObjectWithQuotedKeys() {
        ScriptConfig config = ScriptConfig.parse("export const config = { 'onOrphan': 'resume', \"maxAttempts\": 4 };");

        assertThat(config.onOrphan()).isEqualTo(OrphanPolicy.RESUME);
        assertThat(config.maxAttempts()).isEqualTo(4);
    }

    @Test
    void policyValuesAndKeysAreCaseInsensitive() {
        assertThat(ScriptConfig.parse("export const config = { ONORPHAN: 'ReTrY' };").onOrphan()).isEqualTo(OrphanPolicy.RETRY);
        assertThat(ScriptConfig.parse("export const onOrphan = \"RESUME\";").onOrphan()).isEqualTo(OrphanPolicy.RESUME);
    }

    @Test
    void toleratesCommentsWhitespaceMultilineAndTrailingCommas() {
        ScriptConfig config = ScriptConfig.parse("""
                // Which policy this job wants.
                export const config = {
                  /* the important bit */
                  onOrphan: 'retry', // repeat safely
                  maxAttempts: 5,
                  timeoutMs: 45000,
                };
                export default () => 1;
                """);

        assertThat(config.onOrphan()).isEqualTo(OrphanPolicy.RETRY);
        assertThat(config.maxAttempts()).isEqualTo(5);
        assertThat(config.timeoutMs()).isEqualTo(45_000L);
    }

    @Test
    void readsDirectExports() {
        ScriptConfig config = ScriptConfig.parse("""
                export const onOrphan = 'resume';
                export const maxAttempts = 7;
                export const timeoutMs = 12000;
                export default () => 1;
                """);

        assertThat(config).isEqualTo(new ScriptConfig(OrphanPolicy.RESUME, 7, 12_000L));
    }

    @Test
    void readsADirectExportEvenAmongOtherCode() {
        ScriptConfig config = ScriptConfig.parse("""
                const helper = 1;
                export let onOrphan = "retry";
                console.log(helper);
                export default () => 1;
                """);

        assertThat(config.onOrphan()).isEqualTo(OrphanPolicy.RETRY);
        assertThat(config.maxAttempts()).isNull();
        assertThat(config.timeoutMs()).isNull();
    }

    @Test
    void configObjectWinsAndFallsBackForMissingFields() {
        ScriptConfig config = ScriptConfig.parse("""
                export const onOrphan = 'fail';
                export const maxAttempts = 9;
                export const config = { onOrphan: 'resume' };
                """);

        assertThat(config.onOrphan()).isEqualTo(OrphanPolicy.RESUME);
        assertThat(config.maxAttempts()).isEqualTo(9);
    }

    @Test
    void ignoresAnUnknownPolicy() {
        assertThat(ScriptConfig.parse("export const config = { onOrphan: 'sometimes' };").onOrphan()).isNull();
        assertThat(ScriptConfig.parse("export const config = { onOrphan: 'sometimes', maxAttempts: 2 };").maxAttempts()).isEqualTo(2);
    }

    @Test
    void ignoresMaxAttemptsOutsideTheAllowedRange() {
        assertThat(ScriptConfig.parse("export const config = { maxAttempts: 0 };").maxAttempts()).isNull();
        assertThat(ScriptConfig.parse("export const config = { maxAttempts: -3 };").maxAttempts()).isNull();
        assertThat(ScriptConfig.parse("export const config = { maxAttempts: 11 };").maxAttempts()).isNull();
        assertThat(ScriptConfig.parse("export const config = { maxAttempts: 10 };").maxAttempts())
                .isEqualTo(OrphanPolicy.MAX_ATTEMPTS_LIMIT);
        assertThat(ScriptConfig.parse("export const maxAttempts = 1;").maxAttempts()).isEqualTo(1);
    }

    @Test
    void ignoresNonPositiveOrNonNumericNumbers() {
        assertThat(ScriptConfig.parse("export const config = { timeoutMs: 0 };").timeoutMs()).isNull();
        assertThat(ScriptConfig.parse("export const config = { timeoutMs: -1 };").timeoutMs()).isNull();
        assertThat(ScriptConfig.parse("export const config = { timeoutMs: 'soon' };").timeoutMs()).isNull();
        assertThat(ScriptConfig.parse("export const config = { maxAttempts: 'three' };").maxAttempts()).isNull();
    }

    @Test
    void ignoresCommentedOutDeclarations() {
        assertThat(ScriptConfig.parse("// export const config = { onOrphan: 'retry' };").onOrphan()).isNull();
        assertThat(ScriptConfig.parse("/* export const config = { onOrphan: 'retry' }; */").onOrphan()).isNull();
        assertThat(ScriptConfig.parse("// export const onOrphan = 'resume';").onOrphan()).isNull();
    }

    @Test
    void aUrlInAStringDoesNotSwallowTheRestOfTheScript() {
        ScriptConfig config = ScriptConfig.parse("""
                const url = 'http://example.com/onOrphan';
                export const config = { onOrphan: 'resume' };
                """);

        assertThat(config.onOrphan()).isEqualTo(OrphanPolicy.RESUME);
    }

    @Test
    void findsTheClosingBracePastBracesInStrings() {
        ScriptConfig config = ScriptConfig.parse("export const config = { description: \"includes } brace\", onOrphan: \"resume\" };");

        assertThat(config.onOrphan()).isEqualTo(OrphanPolicy.RESUME);
    }

    @Test
    void readsNumericLiteralsWithUnderscoreSeparators() {
        ScriptConfig config = ScriptConfig.parse("export const config = { timeoutMs: 30_000, maxAttempts: 5 };");

        assertThat(config.timeoutMs()).isEqualTo(30_000L);
        assertThat(config.maxAttempts()).isEqualTo(5);
    }

    @Test
    void scansNestedObjectsForTheClosingBrace() {
        ScriptConfig config = ScriptConfig.parse("export const config = { details: { nested: true }, onOrphan: \"retry\" };");

        assertThat(config.onOrphan()).isEqualTo(OrphanPolicy.RETRY);
    }

    @Test
    void returnsEmptyWhenOnlyInvalidValuesAreDeclared() {
        assertThat(ScriptConfig.parse("export const config = { onOrphan: 'nope', maxAttempts: 0, timeoutMs: -5 };"))
                .isEqualTo(ScriptConfig.EMPTY);
    }

    @Test
    void keepsOnlyTheFieldsThatAreValid() {
        ScriptConfig config = ScriptConfig.parse("export const config = { onOrphan: 'retry', maxAttempts: 99 };");

        assertThat(config.onOrphan()).isEqualTo(OrphanPolicy.RETRY);
        assertThat(config.maxAttempts()).isNull();
        assertThat(config.timeoutMs()).isNull();
    }
}
