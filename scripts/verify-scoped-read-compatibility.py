#!/usr/bin/env python3
"""Compile captured-baseline clients and execute the documented scoped read examples."""

import argparse
import hashlib
import json
import os
from pathlib import Path
import re
import subprocess
import sys
import tempfile
import xml.etree.ElementTree as ET
import zipfile


ROOT = Path(__file__).resolve().parents[1]
OWNERS = {
    'com.embabel.dice.agent.Memory': 'dice',
    'com.embabel.dice.proposition.PropositionQuery': 'dice',
    'com.embabel.dice.storage.DrivinePropositionRepository': 'dice-storage',
    'com.embabel.dice.storage.autoconfigure.DiceStoreProperties': 'dice-storage-autoconfigure',
    'com.embabel.dice.metamodel.MetamodelVersion': 'dice-metamodel',
}
DOCS = {
    'scoped-proposition-reads.md': ('ScopedReadsJava', 'ScopedReadsKotlin'),
    'filtered-vector-search.md': ('FilteredVectorsJava', 'FilteredVectorsKotlin'),
    'authorized-multi-context-memory.md': ('MultiContextMemoryJava', 'MultiContextMemoryKotlin'),
    'memory-visibility.md': ('MemoryVisibilityJava', 'MemoryVisibilityKotlin'),
}


def run(command):
    result = subprocess.run(command, text=True, stdout=subprocess.PIPE, stderr=subprocess.STDOUT)
    if result.returncode:
        raise RuntimeError(f'Command failed ({result.returncode}): {command[0]}\n{result.stdout}')
    return result.stdout


def classpath(value):
    if not value or any(not entry for entry in value.split(os.pathsep)):
        raise ValueError('An explicit nonempty classpath is required; empty entries are forbidden')
    paths = [Path(entry).expanduser().resolve(strict=True) for entry in value.split(os.pathsep)]
    return os.pathsep.join(map(str, paths)), paths


def origin(paths, name):
    member = name.replace('.', '/') + '.class'
    for path in paths:
        if path.is_dir() and (path / member).is_file():
            return path
        if path.is_file() and zipfile.is_zipfile(path):
            with zipfile.ZipFile(path) as jar:
                if member in jar.namelist():
                    return path
    raise ValueError(f'Classpath has no {name}')


def verify_origins(baseline, candidate):
    old = {name: origin(baseline, name) for name in OWNERS}
    new = {name: origin(candidate, name) for name in OWNERS}
    for name, module in OWNERS.items():
        expected = (ROOT / module / 'target/classes').resolve()
        if new[name] != expected:
            raise ValueError(f'Candidate {name} must come from fresh {module}/target/classes, got {new[name]}')
        if old[name] == new[name] or not old[name].is_file():
            raise ValueError(f'Baseline {name} must come from a distinct captured classes JAR')
    manifest_path = old['com.embabel.dice.agent.Memory'].parent / 'manifest.json'
    if not manifest_path.is_file():
        raise ValueError('Captured baseline manifest.json is required beside the baseline core JAR')
    manifest = json.loads(manifest_path.read_text())
    if not manifest.get('commit'):
        raise ValueError('Baseline manifest must identify its captured Git commit')
    for name, module in OWNERS.items():
        entry = manifest if module == 'dice' else manifest[module]
        path = old[name]
        if path.name != entry['classes_jar'] or hashlib.sha256(path.read_bytes()).hexdigest() != entry['sha256']:
            raise ValueError(f'Baseline provenance mismatch for {module}')
    print(f'PASS baseline manifest and candidate origins ({len(OWNERS)} classes; baseline {manifest["commit"]})')
    return old, new


def descriptors(cp, name):
    output = run(['javap', '-p', '-s', '-classpath', cp, name])
    selected = set()
    declaration = ''
    for line in output.splitlines():
        if '(' in line and line.rstrip().endswith(';') and not line.strip().startswith('descriptor:'):
            declaration = line.strip()
        elif line.strip().startswith('descriptor:') and declaration:
            method = declaration.split('(')[0].split()[-1]
            constructor = method == name
            retained = constructor or (
                name != 'com.embabel.dice.storage.DrivinePropositionRepository'
                and (method.startswith(('copy', 'component', 'forContext', 'againstContext'))
                     or method.endswith('$default') or '$' in name)
            )
            if retained:
                visibility = next((v for v in ('public', 'protected', 'private') if v in declaration.split()), 'package')
                selected.add((method, line.strip(), visibility))
            declaration = ''
    if not selected:
        raise ValueError(f'No retained descriptors selected for {name}')
    return selected


def compare_abi(baseline_cp, candidate_cp):
    total = 0
    classes = list(OWNERS)[:4] + [
        'com.embabel.dice.agent.Memory$Companion',
        'com.embabel.dice.agent.Memory$WithContext',
        'com.embabel.dice.proposition.PropositionQuery$Companion',
    ]
    for name in classes:
        before = descriptors(baseline_cp, name)
        after = descriptors(candidate_cp, name)
        component_count = {
            'com.embabel.dice.agent.Memory': 12,
            'com.embabel.dice.proposition.PropositionQuery': 24,
            'com.embabel.dice.storage.autoconfigure.DiceStoreProperties': 3,
        }.get(name)
        if component_count is not None:
            components = {re.match(r'component\d+', method).group() for method, _, _ in before
                          if re.match(r'component\d+', method)}
            if components != {f'component{index}' for index in range(1, component_count + 1)}:
                raise ValueError(f'Incomplete baseline component inventory for {name}')
            if not any(method.startswith('copy') and method.endswith('$default') for method, _, _ in before):
                raise ValueError(f'Missing baseline default-copy descriptor for {name}')
        missing = before - after
        if missing:
            raise ValueError(f'Retained ABI missing in {name}: {sorted(missing)}')
        total += len(before)
        print(f'PASS retained descriptors {name}: {len(before)}')
    return total


def compiler_classpath(cache):
    namespace = {'m': 'http://maven.apache.org/POM/4.0.0'}
    resolved = {}

    def resolve(group, artifact, version, excluded=frozenset()):
        key = (group, artifact)
        if key in resolved:
            return
        base = cache / group.replace('.', '/') / artifact / version / f'{artifact}-{version}'
        jar, pom = Path(str(base) + '.jar'), Path(str(base) + '.pom')
        if not jar.is_file() or not pom.is_file():
            raise ValueError(f'Cached Kotlin compiler dependency unavailable: {group}:{artifact}:{version}')
        resolved[key] = jar
        if ('*', '*') in excluded:
            return
        tree = ET.parse(pom).getroot()
        properties = {node.tag.split('}')[-1]: node.text for node in tree.findall('m:properties/*', namespace)}
        properties.update({'project.version': version, 'pom.version': version})

        def value(node, field, default=None):
            text = node.findtext(f'm:{field}', default, namespace)
            if text is not None:
                for prop, replacement in properties.items():
                    text = text.replace('${' + prop + '}', replacement)
                if '${' in text:
                    raise ValueError(f'Unresolved cached compiler POM value: {text}')
            return text

        for dependency in tree.findall('m:dependencies/m:dependency', namespace):
            if value(dependency, 'scope', 'compile') in ('test', 'provided', 'system') or value(dependency, 'optional') == 'true':
                continue
            exclusions = frozenset((value(e, 'groupId'), value(e, 'artifactId'))
                                   for e in dependency.findall('m:exclusions/m:exclusion', namespace))
            if (value(dependency, 'groupId'), value(dependency, 'artifactId')) in excluded:
                continue
            dep_version = value(dependency, 'version')
            if dep_version is None:
                raise ValueError('Cached compiler POM has an unresolved dependency version')
            resolve(value(dependency, 'groupId'), value(dependency, 'artifactId'), dep_version, excluded | exclusions)

    resolve('org.jetbrains.kotlin', 'kotlin-compiler-embeddable', '2.2.21')
    cp = os.pathsep.join(str(path) for path in resolved.values())
    version = run(['java', '-cp', cp, 'org.jetbrains.kotlin.cli.jvm.K2JVMCompiler', '-version'])
    if '2.2.21' not in version:
        raise ValueError('The cached Kotlin compiler is not 2.2.21')
    print(f'PASS cached Kotlin compiler 2.2.21 ({len(resolved)} artifacts)')
    return cp


ORIGIN_RUNNER = r'''
import java.nio.file.Path;
public class RuntimeOrigins {
    public static void main(String[] args) throws Exception {
        for (int i = 1; i < args.length; i += 2) {
            var type = Class.forName(args[i]);
            var location = Path.of(type.getProtectionDomain().getCodeSource().getLocation().toURI()).toRealPath();
            if (!location.equals(Path.of(args[i + 1]).toRealPath())) {
                throw new AssertionError("Wrong runtime origin for " + args[i] + ": " + location);
            }
        }
        Class.forName(args[0]).getMethod("main", String[].class).invoke(null, (Object) new String[0]);
    }
}
'''

LEGACY_JAVA = r'''
import com.embabel.dice.agent.Memory;
import com.embabel.dice.proposition.PropositionQuery;
import com.embabel.dice.proposition.store.InMemoryPropositionRepository;
import com.embabel.dice.storage.DrivinePropositionRepository;
import com.embabel.dice.storage.autoconfigure.DiceStoreProperties;
import com.embabel.common.ai.model.EmbeddingService;
import org.drivine.manager.PersistenceManager;
import org.drivine.connection.DatabaseType;
import org.drivine.manager.StatelessGraphObjectManager;
import org.drivine.query.grammar.CypherGrammar;
import org.mockito.Mockito;
import org.springframework.transaction.PlatformTransactionManager;
import java.lang.reflect.Proxy;

public class LegacyJava {
    public static <T> T dependency(Class<T> type) {
        return type.cast(Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[] {type}, (p, m, a) -> {
            if (m.getName().equals("toString")) return type.getSimpleName();
            if (type == PersistenceManager.class && m.getName().equals("getType")) return DatabaseType.NEO4J;
            if (type == PersistenceManager.class && m.getName().equals("getDatabase")) return "compatibility";
            if (type == PersistenceManager.class && m.getName().equals("getGrammar")) return dependency(CypherGrammar.class);
            throw new AssertionError("Unexpected dependency call: " + m);
        }));
    }
    public static StatelessGraphObjectManager manager(PersistenceManager persistence) {
        return Mockito.mock(StatelessGraphObjectManager.class);
    }
    public static void main(String[] args) {
        var store = new InMemoryPropositionRepository();
        var memory = Memory.forContext("A").withRepository(store).withTopic("legacy").withMinConfidence(0.5);
        if (memory.tools().size() != 1 || memory.call("{}") == null) throw new AssertionError("Memory call");
        var query = PropositionQuery.againstContext("A").withMinConfidence(0.5).withLimit(2);
        if (!store.query(query).isEmpty()) throw new AssertionError("Query");
        var defaults = new DiceStoreProperties();
        var properties = new DiceStoreProperties("graph", defaults.getDecay(), defaults.getVectorIndex());
        if (!properties.copy("in-memory", properties.getDecay(), properties.getVectorIndex()).getType().equals("in-memory")) {
            throw new AssertionError("Properties copy");
        }
        var persistence = dependency(PersistenceManager.class);
        new DrivinePropositionRepository(manager(persistence), persistence, dependency(EmbeddingService.class),
                dependency(PlatformTransactionManager.class), "baseline-vector-index");
        System.out.println("LegacyJava OK");
    }
}
'''

LEGACY_KOTLIN = r'''
import com.embabel.dice.agent.Memory
import com.embabel.agent.core.ContextId
import com.embabel.dice.proposition.PropositionQuery
import com.embabel.dice.proposition.store.InMemoryPropositionRepository
import com.embabel.dice.storage.DrivinePropositionRepository
import com.embabel.dice.storage.autoconfigure.DiceStoreProperties
import com.embabel.common.ai.model.EmbeddingService
import org.drivine.manager.PersistenceManager
import org.springframework.transaction.PlatformTransactionManager

fun main() {
    val store = InMemoryPropositionRepository()
    val memory = Memory(ContextId("A"), store).copy(topic = "legacy")
    check(memory.tools().size == 1 && memory.call("{}") != null)
    check(Memory.forContext(ContextId("A")).withRepository(store).tools().size == 1)
    val query = PropositionQuery(contextId = ContextId("A"), minConfidence = 0.5).copy(limit = 2)
    val (context, entity) = query
    check(context == ContextId("A") && entity == null && store.query(query).isEmpty())
    val properties = DiceStoreProperties().copy(type = "graph")
    val (type, decay, index) = properties
    check(type == "graph" && decay.enabled && index.enabled)
    val persistence = LegacyJava.dependency(PersistenceManager::class.java)
    val manager = LegacyJava.manager(persistence)
    val embedding = LegacyJava.dependency(EmbeddingService::class.java)
    val transactions = LegacyJava.dependency(PlatformTransactionManager::class.java)
    DrivinePropositionRepository(manager, persistence, embedding, transactions)
    DrivinePropositionRepository(manager, persistence, embedding, transactions, "baseline-vector-index")
    println("LegacyKotlin OK")
}
'''


def compile_kotlin(compiler_cp, fixture_cp, output, source):
    run(['java', '-cp', compiler_cp, 'org.jetbrains.kotlin.cli.jvm.K2JVMCompiler',
         '-jvm-target', '21', '-no-stdlib', '-no-reflect', '-classpath', fixture_cp,
         '-d', str(output), str(source)])


def execute(output, cp, main, origins):
    arguments = [item for pair in origins.items() for item in (pair[0], str(pair[1]))]
    agent = []
    if main.startswith('Legacy'):
        mocks = [entry for entry in cp.split(os.pathsep) if Path(entry).name.startswith('mockito-core-')]
        if len(mocks) != 1:
            raise ValueError('Legacy constructor fixtures require the captured Mockito core dependency')
        agent = ['-javaagent:' + mocks[0]]
    result = run(['java', *agent, '-ea', '-cp', str(output) + os.pathsep + cp, 'RuntimeOrigins', main, *arguments])
    expected = main.removesuffix('Kt') + ' OK'
    if expected not in result:
        raise ValueError(f'Fixture failed to report completion: {main}\n{result}')
    print(f'PASS execution {main} (checked runtime origins): {expected}')


def verify_clients(temporary, baseline_cp, candidate_cp, compiler_cp, baseline_origins, candidate_origins):
    output = temporary / 'legacy'
    output.mkdir()
    for name, content in [('RuntimeOrigins.java', ORIGIN_RUNNER), ('LegacyJava.java', LEGACY_JAVA),
                          ('LegacyKotlin.kt', LEGACY_KOTLIN)]:
        (temporary / name).write_text(content)
    run(['javac', '--release', '21', '-classpath', baseline_cp, '-d', str(output),
         str(temporary / 'RuntimeOrigins.java'), str(temporary / 'LegacyJava.java')])
    compile_kotlin(compiler_cp, str(output) + os.pathsep + baseline_cp, output, temporary / 'LegacyKotlin.kt')
    print('PASS old Java and Kotlin clients compiled against captured baseline only')
    for cp, origins in [(baseline_cp, baseline_origins), (candidate_cp, candidate_origins)]:
        for main in ('LegacyJava', 'LegacyKotlinKt'):
            execute(output, cp, main, origins)


def verify_docs(temporary, candidate_cp, compiler_cp, origins):
    total = 0
    for filename, names in DOCS.items():
        text = (ROOT / 'docs/design' / filename).read_text()
        for language, name in zip(('java', 'kotlin'), names):
            blocks = re.findall(r'^```' + language + r'\s*\n(.*?)^```\s*$', text, re.MULTILINE | re.DOTALL)
            if len(blocks) != 1:
                raise ValueError(f'{filename} must contain exactly one complete {language} example')
            directory = temporary / name
            directory.mkdir()
            extension = 'java' if language == 'java' else 'kt'
            source = directory / f'{name}.{extension}'
            source.write_text(blocks[0])
            helper = directory / 'RuntimeOrigins.java'
            helper.write_text(ORIGIN_RUNNER)
            run(['javac', '--release', '21', '-classpath', candidate_cp, '-d', str(directory), str(helper)])
            if language == 'java':
                run(['javac', '--release', '21', '-classpath', candidate_cp, '-d', str(directory), str(source)])
            else:
                compile_kotlin(compiler_cp, candidate_cp, directory, source)
            execute(directory, candidate_cp, name if language == 'java' else name + 'Kt', origins)
            total += 1
    return total


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--baseline-classpath', required=True)
    parser.add_argument('--candidate-classpath', required=True)
    parser.add_argument('--maven-cache', type=Path, default=Path.home() / '.m2/repository')
    args = parser.parse_args()
    baseline_cp, baseline = classpath(args.baseline_classpath)
    candidate_cp, candidate = classpath(args.candidate_classpath)
    old, new = verify_origins(baseline, candidate)
    descriptors_checked = compare_abi(baseline_cp, candidate_cp)
    compiler_cp = compiler_classpath(args.maven_cache)
    with tempfile.TemporaryDirectory(prefix='dice-read-compatibility-') as directory:
        temporary = Path(directory)
        verify_clients(temporary, baseline_cp, candidate_cp, compiler_cp, old, new)
        examples = verify_docs(temporary, candidate_cp, compiler_cp, new)
    print(f'PASS scoped read compatibility: {descriptors_checked} retained descriptors; '
          f'2 baseline-compiled clients x 2 runtimes; {examples} documented examples')


if __name__ == '__main__':
    try:
        main()
    except (ValueError, RuntimeError, OSError, KeyError, zipfile.BadZipFile, ET.ParseError) as error:
        print(f'FAIL scoped read compatibility: {error}', file=sys.stderr)
        sys.exit(1)
