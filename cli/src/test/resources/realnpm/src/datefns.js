import { addDays, format, differenceInCalendarDays, parseISO, formatISO } from 'date-fns';
export const run = () => {
  const d = parseISO('2026-10-08T12:00:00Z');
  return { plus: formatISO(addDays(d, 30), { representation: 'date' }), diff: differenceInCalendarDays(parseISO('2026-12-25'), parseISO('2026-10-08')),
    formatted: format(parseISO('2026-10-08'), 'yyyy/MM/dd') };
};
