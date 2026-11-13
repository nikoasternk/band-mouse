import configuration from '@system.configuration'

const DICTIONARY = {
  en: {
    menuTopBtn: 'Menu',
    backToPad: '← Touchpad',
    back: '← Back',
    modeTitle: 'Control Mode',
    modeCursor: 'CURSOR',
    modeScroll: 'SCROLL',
    modeHybrid: 'HYBRID',
    settings: 'Settings',
    exitApp: 'Exit App',
    saveSettings: 'Save Settings',
    savedStatus: 'Saved ✓',
    language: 'Language',
    langAuto: 'Auto',
    langEn: 'English',
    langRu: 'Русский',
    dragScrollTitle: 'Cursor Drag Scroll',
    vibrationTitle: 'Vibration',
    tapLockTitle: 'Click Stabilizer',
    hybridHoldTitle: 'Hybrid Hold',
    menuSizeTitle: 'Menu Btn Size',
    navBarTitle: 'Phone Bar (◀ ● ■)',
    navSizeTitle: 'Phone Bar Size',
    stealthSquareTitle: 'Stealth on ■',
    stealthHoldTitle: 'Hold ■ Time',
    keepScreenOn: 'Keep Screen On',
    customBrightness: 'Custom Brightness',
    brightness: 'Brightness',
    enabled: 'ON',
    disabled: 'OFF',
    resetSettings: 'Reset Settings',
    resetConfirm: 'Confirm Reset?'
  },
  ru: {
    menuTopBtn: 'Меню',
    backToPad: '← Тачпад',
    back: '← Назад',
    modeTitle: 'Режим управления',
    modeCursor: 'КУРСОР',
    modeScroll: 'СКРОЛЛ',
    modeHybrid: 'ГИБРИД',
    settings: 'Настройки',
    exitApp: 'Выход',
    saveSettings: 'Сохранить',
    savedStatus: 'Сохранено ✓',
    language: 'Язык',
    langAuto: 'Авто',
    langEn: 'English',
    langRu: 'Русский',
    dragScrollTitle: 'Скролл курсором',
    vibrationTitle: 'Вибрация',
    tapLockTitle: 'Стабилизация клика',
    hybridHoldTitle: 'Удержание гибрида',
    menuSizeTitle: 'Размер кн. Меню',
    navBarTitle: 'Панель телефона',
    navSizeTitle: 'Размер панели',
    stealthSquareTitle: 'Скрытность на ■',
    stealthHoldTitle: 'Удержание ■',
    keepScreenOn: 'Не гасить экран',
    customBrightness: 'Своя яркость',
    brightness: 'Яркость',
    enabled: 'ВКЛ',
    disabled: 'ВЫКЛ',
    resetSettings: 'Сброс настроек',
    resetConfirm: 'Точно сбросить?'
  }
};

export function resolveLanguageCode(preferredMode) {
  if (preferredMode === 'en' || preferredMode === 'ru') {
    return preferredMode;
  }
  try {
    const locale = configuration.getLocale();
    if (locale && locale.language && locale.language.toLowerCase().indexOf('ru') === 0) {
      return 'ru';
    }
  } catch (e) {}
  return 'en';
}

export function getStrings(preferredMode) {
  const code = resolveLanguageCode(preferredMode || 'auto');
  return DICTIONARY[code] || DICTIONARY.en;
}

export function pctToHardwareBrightness(visualPct) {
  const clamped = Math.max(1, Math.min(100, visualPct || 80));
  const minHw = 28;
  const maxHw = 255;
  return Math.round(minHw + ((clamped - 1) / 99) * (maxHw - minHw));
}

export function fitFontSize(text, maxWidthPx, maxFontPx, minFontPx, singleLine) {
  if (!text) return maxFontPx;
  const str = String(text);
  const words = str.split(' ');
  let longestWord = 1;
  for (let i = 0; i < words.length; i++) {
    if (words[i].length > longestWord) {
      longestWord = words[i].length;
    }
  }
  const byLongestWord = Math.floor(maxWidthPx / (longestWord * 0.64));
  let size = Math.min(maxFontPx, byLongestWord);
  if (singleLine) {
    const byFullLen = Math.floor(maxWidthPx / (Math.max(1, str.length) * 0.60));
    size = Math.min(size, byFullLen);
  } else if (str.length > 16) {
    const byTwoLines = Math.floor((maxWidthPx * 1.85) / (str.length * 0.60));
    size = Math.min(size, byTwoLines);
  }
  return Math.max(minFontPx, size);
}
