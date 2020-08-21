/**
 * @license
 * Copyright (C) 2017 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

import '../../../test/common-test-setup-karma.js';
import './gr-dropdown-list.js';

const basicFixture = fixtureFromElement('gr-dropdown-list');

suite('gr-dropdown-list tests', () => {
  let element;

  setup(() => {
    stub('gr-rest-api-interface', {
      getConfig() { return Promise.resolve({}); },
    });
    element = basicFixture.instantiate();
  });

  test('tap on trigger opens menu', () => {
    sinon.stub(element, '_open')
        .callsFake(() => { element.$.dropdown.open(); });
    assert.isFalse(element.$.dropdown.opened);
    MockInteractions.tap(element.$.trigger);
    assert.isTrue(element.$.dropdown.opened);
  });

  test('_computeMobileText', () => {
    const item = {
      value: 1,
      text: 'text',
    };
    assert.equal(element._computeMobileText(item), item.text);
    item.mobileText = 'mobile text';
    assert.equal(element._computeMobileText(item), item.mobileText);
  });

  test('options are selected and laid out correctly', done => {
    element.value = 2;
    element.items = [
      {
        value: 1,
        text: 'Top Text 1',
      },
      {
        value: 2,
        bottomText: 'Bottom Text 2',
        triggerText: 'Button Text 2',
        text: 'Top Text 2',
        mobileText: 'Mobile Text 2',
      },
      {
        value: 3,
        disabled: true,
        bottomText: 'Bottom Text 3',
        triggerText: 'Button Text 3',
        date: '2017-08-18 23:11:42.569000000',
        text: 'Top Text 3',
        mobileText: 'Mobile Text 3',
      },
    ];
    assert.equal(element.shadowRoot
        .querySelector('paper-listbox').selected, element.value);
    assert.equal(element.text, 'Button Text 2');
    flush(() => {
      const items = element.root.querySelectorAll('paper-item');
      const mobileItems = element.root.querySelectorAll('option');
      assert.equal(items.length, 3);
      assert.equal(mobileItems.length, 3);

      // First Item
      // The first item should be disabled, has no bottom text, and no date.
      assert.isFalse(!!items[0].disabled);
      assert.isFalse(mobileItems[0].disabled);
      assert.isFalse(items[0].classList.contains('iron-selected'));
      assert.isFalse(mobileItems[0].selected);

      assert.isNotOk(items[0].querySelector('gr-date-formatter'));
      assert.isNotOk(items[0].querySelector('.bottomContent'));
      assert.equal(items[0].dataset.value, element.items[0].value);
      assert.equal(mobileItems[0].value, element.items[0].value);
      assert.equal(items[0].querySelector('.topContent div')
          .innerText, element.items[0].text);

      // Since no mobile specific text, it should fall back to text.
      assert.equal(mobileItems[0].text, element.items[0].text);

      // Second Item
      // The second item should have top text, bottom text, and no date.
      assert.isFalse(!!items[1].disabled);
      assert.isFalse(mobileItems[1].disabled);
      assert.isTrue(items[1].classList.contains('iron-selected'));
      assert.isTrue(mobileItems[1].selected);

      assert.isNotOk(items[1].querySelector('gr-date-formatter'));
      assert.isOk(items[1].querySelector('.bottomContent'));
      assert.equal(items[1].dataset.value, element.items[1].value);
      assert.equal(mobileItems[1].value, element.items[1].value);
      assert.equal(items[1].querySelector('.topContent div')
          .innerText, element.items[1].text);

      // Since there is mobile specific text, it should that.
      assert.equal(mobileItems[1].text, element.items[1].mobileText);

      // Since this item is selected, and it has triggerText defined, that
      // should be used.
      assert.equal(element.text, element.items[1].triggerText);

      // Third item
      // The third item should be disabled, and have a date, and bottom content.
      assert.isTrue(!!items[2].disabled);
      assert.isTrue(mobileItems[2].disabled);
      assert.isFalse(items[2].classList.contains('iron-selected'));
      assert.isFalse(mobileItems[2].selected);

      assert.isOk(items[2].querySelector('gr-date-formatter'));
      assert.isOk(items[2].querySelector('.bottomContent'));
      assert.equal(items[2].dataset.value, element.items[2].value);
      assert.equal(mobileItems[2].value, element.items[2].value);
      assert.equal(items[2].querySelector('.topContent div')
          .innerText, element.items[2].text);

      // Since there is mobile specific text, it should that.
      assert.equal(mobileItems[2].text, element.items[2].mobileText);

      // Select a new item.
      MockInteractions.tap(items[0]);
      flushAsynchronousOperations();
      assert.equal(element.value, 1);
      assert.isTrue(items[0].classList.contains('iron-selected'));
      assert.isTrue(mobileItems[0].selected);

      // Since no triggerText, the fallback is used.
      assert.equal(element.text, element.items[0].text);
      done();
    });
  });
});

