import { marked } from 'marked';
export const run = () => ({ html: marked.parse('# Title\n\nSome *emphasis* and `code`.').trim() });
