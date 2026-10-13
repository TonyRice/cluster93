import Papa from 'papaparse';
export const run = () => {
  const parsed = Papa.parse('name,qty\napple,3\npear,5\n', { header: true, dynamicTyping: true, skipEmptyLines: true });
  return { rows: parsed.data, csv: Papa.unparse([{ a: 1, b: 'x,y' }]) };
};
