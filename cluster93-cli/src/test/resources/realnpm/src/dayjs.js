import dayjs from 'dayjs';
import utc from 'dayjs/plugin/utc.js';
dayjs.extend(utc);
export const run = () => ({ start: dayjs.utc('2026-10-08T10:20:30Z').startOf('month').toISOString(), add: dayjs.utc('2026-01-31').add(1, 'month').format('YYYY-MM-DD') });
