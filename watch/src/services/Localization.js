import configuration from '@system.configuration'

const DICTIONARY = {
  en: {
    backBtn: '◄ Back',
    ready: 'Touchpad',
    connected: 'Connected',
    hintCursor: 'Move / Tap = Click',
    hintScroll: 'Swipe Up / Down',
    modeCursor: 'MODE: CURSOR',
    modeScroll: 'MODE: SCROLL'
  },
  ru: {
    backBtn: '◄ Назад',
    ready: 'Тачпад',
    connected: 'Связь ОК',
    hintCursor: 'Води / Тап = Клик',
    hintScroll: 'Листай вверх/вниз',
    modeCursor: 'РЕЖИМ: КУРСОР',
    modeScroll: 'РЕЖИМ: СКРОЛЛ'
  }
};

export function getStrings() {
  try {
    const locale = configuration.getLocale();
    if (locale && locale.language && locale.language.toLowerCase().indexOf('ru') === 0) {
      return DICTIONARY.ru;
    }
  } catch (e) {}
  return DICTIONARY.en;
}