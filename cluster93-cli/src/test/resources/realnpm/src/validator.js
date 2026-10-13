import validator from 'validator';
import { camelCase, snakeCase } from 'change-case';
import { XMLParser } from 'fast-xml-parser';
import qs from 'qs';
export const run = () => ({ email: validator.isEmail('a@b.co'), url: validator.isURL('https://x.y/z'), camel: camelCase('hello big world'), snake: snakeCase('helloBigWorld'),
  xml: new XMLParser().parse('<r><a>1</a><b>two</b></r>'), qs: qs.parse('a[b]=1&c=2'), qsOut: qs.stringify({ a: { b: 1 } }) });
