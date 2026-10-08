import Ajv from 'ajv';
export const run = () => {
  const ajv = new Ajv({ allErrors: true });
  const validate = ajv.compile({ type: 'object', properties: { n: { type: 'integer', minimum: 1 } }, required: ['n'], additionalProperties: false });
  return { ok: validate({ n: 2 }), bad: validate({ n: 0, extra: true }), errors: validate.errors.map(e => e.keyword).sort() };
};
