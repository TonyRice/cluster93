package automately.core.packages;

import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ImportsTest {

    @Test
    void findsEveryImportAndExportForm() {
        String source = """
                import 'side-effect';
                import a from "default-only";
                import { b, c as d } from 'named';
                import * as ns from 'namespace';
                import e, { f } from 'default-and-named';
                import g, * as h from 'default-and-namespace';
                import { from } from 'a-name-called-from';
                export * from 'reexported';
                export * as more from 'reexported-as';
                export { x, y as z } from 'reexported-named';
                export { local };
                export const value = 1;
                const later = await import('dynamic');
                """;
        assertThat(Imports.scan(source).specifiers()).containsExactly("side-effect", "default-only", "named", "namespace", "default-and-named",
                "default-and-namespace", "a-name-called-from", "reexported", "reexported-as", "reexported-named", "dynamic");
    }

    @Test
    void ignoresWhatOnlyLooksLikeAnImport() {
        String source = """
                // import 'in-a-line-comment';
                /* import 'in-a-block-comment'; */
                const s = "import 'in-a-string'";
                const t = `import 'in-a-template' ${ "import('in-an-expression-string')" } and ${ { a: 1 }.a } import('after')`;
                const r = /import('in-a-regex')/.test(s);
                const q = x / 2 / import.meta.url.length;
                obj.import('a-method');
                export default { from: 'not-an-export' };
                """;
        Imports.Scan scan = Imports.scan(source);
        assertThat(scan.specifiers()).isEmpty();
        assertThat(scan.computed()).isEmpty();
        assertThat(scan.meta()).hasSize(1);
    }

    @Test
    void templatesNestAndResume() {
        String source = "const a = `x ${ `y ${ 1 + 2 } z` } w`; import 'after-templates';";
        assertThat(Imports.scan(source).specifiers()).containsExactly("after-templates");
    }

    @Test
    void anImportWhoseNameIsntALiteralIsComputed() {
        Imports.Scan scan = Imports.scan("const m = await import(name); const n = await import('a' + b); const o = await import('x', { with: {} });");
        assertThat(scan.computed()).hasSize(3);
        assertThat(scan.dynamic()).isEmpty();
    }

    @Test
    void escapedNamesAreDecoded() {
        assertThat(Imports.scan("import x from 'str\\u0069ngs';").specifiers()).containsExactly("strings");
    }

    @Test
    void namesBecomePackagesAndExports() {
        Map<String, Set<String>> wanted = Imports.packages(Imports.scan(
                "import 'fs'; import { readFile } from 'node:fs/promises'; import s from 'strings'; import r from 'strings/report';"
                        + "import d from '@cluster/dates/iso';"));
        assertThat(wanted).containsOnlyKeys("strings", "@cluster/dates");
        assertThat(wanted.get("strings")).containsExactly(".", "./report");
        assertThat(wanted.get("@cluster/dates")).containsExactly("./iso");
    }

    @Test
    void namesThatCantBePackagesAreRefusedWithAReason() {
        assertThatThrownBy(() -> Imports.packages(Imports.scan("import w from 'vm';"))).hasMessageContaining("Node's \"vm\" module");
        assertThatThrownBy(() -> Imports.packages(Imports.scan("import x from 'node:vm';"))).hasMessageContaining("\"vm\"");
        assertThatThrownBy(() -> Imports.packages(Imports.scan("import x from './local.js';"))).hasMessageContaining("is a path or URL");
        assertThatThrownBy(() -> Imports.packages(Imports.scan("import x from 'https://esm.sh/x';"))).hasMessageContaining("is a path or URL");
        assertThatThrownBy(() -> Imports.packages(Imports.scan("import x from 'automately:nope';"))).hasMessageContaining("no built-in module");
        assertThatThrownBy(() -> Imports.packages(Imports.scan("import x from '@acme/x';"))).hasMessageContaining("only scope is @cluster/");
    }

    @Test
    void rewritingKeepsLinesAndHoistsLiteralImports() {
        String source = "export default async () => {\n  const m = await import('strings');\n  return [m.slug('A B'), import.meta.url, import('strings')];\n};";
        String rewritten = Imports.rewrite(source, "file:///packages/app/1.0.0/index.js");
        String[] lines = rewritten.split("\n");
        assertThat(lines[1]).isEqualTo("  const m = await Promise.resolve(__automately_import_0);");
        assertThat(lines[2]).isEqualTo("  return [m.slug('A B'), __automately_meta().url, Promise.resolve(__automately_import_0)];");
        assertThat(rewritten).containsOnlyOnce("import * as __automately_import_0 from \"strings\";")
                .contains("url: \"file:///packages/app/1.0.0/index.js\"");
    }

    @Test
    void sourcesWithNothingToRewriteAreLeftAlone() {
        String source = "import { a } from 'x'; export default () => a; // import (";
        assertThat(Imports.rewrite(source, "file:///x")).isSameAs(source);
    }

    @Test
    void staticSpecifiersAreRewrittenInPlaceAndNothingElse() {
        String source = String.join("\n",
                "import a from 'a';",
                "import 'b';",
                "import { c } from \"c\";",
                "import * as d from 'd';",
                "import e, { f } from 'e';",
                "export * from 'g';",
                "export { h } from 'h';",
                "export * as i from 'i';",
                "const later = import('dynamic');",
                "const text = \"import x from 'not-an-import'\";",
                "// import y from 'commented';",
                "obj.import('method');",
                "export const keep = 'keep';");
        String out = Imports.rewriteStaticSpecifiers(source, name -> name.equals("keep") ? null : "/@m/" + name);
        assertThat(out.lines().toList()).containsExactly(
                "import a from \"/@m/a\";",
                "import \"/@m/b\";",
                "import { c } from \"/@m/c\";",
                "import * as d from \"/@m/d\";",
                "import e, { f } from \"/@m/e\";",
                "export * from \"/@m/g\";",
                "export { h } from \"/@m/h\";",
                "export * as i from \"/@m/i\";",
                "const later = import('dynamic');",
                "const text = \"import x from 'not-an-import'\";",
                "// import y from 'commented';",
                "obj.import('method');",
                "export const keep = 'keep';");
        assertThat(Imports.rewriteStaticSpecifiers("const x = 1;", name -> "/@m/" + name)).isEqualTo("const x = 1;");
    }
}
