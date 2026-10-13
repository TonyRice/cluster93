import { parse } from 'csv-parse';
import { parse as parseSync } from 'csv-parse/sync';
import { Readable } from 'node:stream';
// The streaming parser is a Transform: fed in pieces that split a row, it still yields whole records.
export const run = async () => {
  const records = [];
  const parser = Readable.from(['name,qty\napp', 'le,3\npear,', '5\n']).pipe(parse({ columns: true, cast: true }));
  for await (const record of parser) records.push(record);
  return { streamed: records, sync: parseSync('a;b\n"x;y";2\n', { delimiter: ';' }) };
};
