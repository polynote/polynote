import { FakeSelect } from './fake_select'

export class Toolbar {
    constructor(id, actions) {
        this.element = (id instanceof HTMLElement) ? id : document.getElementById(id);
        this.buttons = [];
        for (const selector in actions) {
            if (actions.hasOwnProperty(selector)) {
                let action = actions[selector];
                let button = this.element.querySelector(selector);

                if (button.classList.contains('dropdown')) {
                    button = new FakeSelect(button);
                } else {
                    button.setState = (state) => {
                        if (state && state !== 'false') {
                            button.classList.add('active');
                        } else {
                            button.classList.remove('active');
                        }
                    };
                    button.command = button.getAttribute('command');
                }

                if (button.id) {
                    this.buttons[button.id] = button;
                }

                button.addEventListener('mousedown', (evt) => evt.preventDefault());
                if (action instanceof Function) {
                    button.addEventListener('click', action);
                } else if (action === 'command' && button.command) {
                    button.addEventListener('mousedown', (e) => {
                        document.execCommand(button.command, false);
                        this.action(e);
                    })
                } else {
                    for (var evt in action) {
                        if (action.hasOwnProperty(evt)) {
                            if (evt === 'getState') {
                                button.getState = action[evt];
                            } else {
                                let handler = action[evt];
                                button.addEventListener(evt, (e) => {
                                    handler(e);
                                    this.action(e);
                                });
                            }
                        }
                    }
                }
                this.buttons.push(button);
            }
        }
    }

    action(event) {}
}