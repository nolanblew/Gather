import { randomUUID } from 'node:crypto';

export function seedWorkspace() {
  const now = new Date().toISOString();
  const categories = ['Everyday', 'Ideas', 'Gifts', 'Trips'].map((name, position) => ({ id: randomUUID(), name, color: ['sage', 'lavender', 'rose', 'sky'][position], position }));
  const specs = [
    ['Weekly groceries', 'A little planning makes the week feel lighter.', 'basket', 'sage', 0, false, [['Produce', ['Avocados', 'Lemons', 'Baby spinach']], ['Pantry & fridge', ['Oat milk', 'Sourdough bread', 'Pasta', 'Eggs']]]],
    ['Birthday gift ideas', 'Thoughtful things for the people we love.', 'gift', 'rose', 2, false, [['', ['A pottery class for Mom', 'A beautiful cookbook for Alex', 'Concert tickets for Sam']]]],
    ['Christmas plans', 'Gather ideas early, enjoy December later.', 'sparkles', 'peach', 2, false, [['Gifts', ['Make a family photo book', 'Choose stocking treats']], ['Traditions', ['Bake gingerbread', 'Plan a cozy movie night']]]],
    ['Project ideas', 'Small sparks worth keeping.', 'lightbulb', 'lavender', 1, false, [['', ['Build a balcony herb garden', 'Organize our favorite recipes', 'Make a reading nook']]]],
    ['Kyoto packing list', 'Room for essentials, and a few new memories.', 'luggage', 'sky', 3, false, [['Essentials', ['Passport', 'Travel adapter', 'Walking shoes']], ['Day bag', ['Water bottle', 'Camera', 'Light rain jacket']]]],
    ['Cabin weekend', 'Our reusable starting point for a quiet escape.', 'home', 'sand', 3, true, [['Kitchen', ['Coffee & filters', 'Breakfast groceries', 'Favorite snacks']], ['Comfort', ['Warm layers', 'Board games', 'A good book']]]],
    ['Camping essentials', 'Ready for fresh air and nights under the stars.', 'tent', 'sage', 3, true, [['Camp', ['Tent & stakes', 'Sleeping bags', 'Headlamps']], ['Cooking', ['Camp stove', 'Matches', 'Water jug']]]],
  ];
  const lists = specs.map(([title, description, icon, color, category, isTemplate, groups], position) => {
    const sections = [], items = [];
    for (const [name, texts] of groups) {
      const sectionId = name ? randomUUID() : null;
      if (name) sections.push({ id: sectionId, title: name, position: sections.length });
      texts.forEach((text, index) => items.push({ id: randomUUID(), text, quantity: text === 'Avocados' ? '3' : text === 'Lemons' ? '2' : text === 'Oat milk' ? '1 carton' : text === 'Headlamps' ? '2' : '', note: text === 'Oat milk' ? 'Unsweetened, please' : text === 'Travel adapter' ? 'Check the USB-C cable too' : text === 'Coffee & filters' ? 'The cabin has a pour-over brewer' : '', checked: position === 0 && ['Avocados', 'Oat milk'].includes(text), sectionId, position: index }));
    }
    return { id: randomUUID(), title, description, icon, color, categoryId: categories[category].id, favorite: position === 0 || position === 4, archived: false, isTemplate, isSample: true, kind: [1,2,3].includes(position) ? 'notes' : 'checklist', repeatCadence: 'manual', createdAt: now, updatedAt: now, position, sections, items };
  });
  return { version: 2, categories, lists };
}
