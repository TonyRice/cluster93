import Mustache from 'mustache';
import Handlebars from 'handlebars';
export const run = () => ({ mustache: Mustache.render('Hi {{name}}{{#items}} [{{.}}]{{/items}}', { name: 'Tony', items: ['a', 'b'] }),
  handlebars: Handlebars.compile('{{#each xs}}<{{this}}>{{/each}} {{upper n}}', {})({ xs: [1, 2], n: 'q' }) });
Handlebars.registerHelper('upper', s => String(s).toUpperCase());
