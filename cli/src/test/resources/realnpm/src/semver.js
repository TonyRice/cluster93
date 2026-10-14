import semver from 'semver';
export const run = () => ({ satisfies: semver.satisfies('1.4.2', '^1.2.0'), max: semver.maxSatisfying(['1.0.0', '1.5.0', '2.0.0'], '^1'), bump: semver.inc('1.4.2', 'minor') });
