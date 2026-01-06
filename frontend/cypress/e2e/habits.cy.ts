describe('Habits', () => {
  beforeEach(() => {
    const email = `testuser${Date.now()}@example.com`;
    cy.visit('/');
    cy.get('.switch-auth button').click();
    cy.get('#email').type(email);
    cy.get('#password').type('password123');
    cy.get('#confirmPassword').type('password123');
    cy.get('button[type="submit"]').click();
    cy.get('h1').contains("Today's Habits", { timeout: 10000 }).should('be.visible');
  });

  it('should create a new habit', () => {
    cy.get('.fab').click();
    cy.get('#habit-name').type('Morning Run');
    cy.get('#habit-description').type('Run 5km every morning');
    cy.get('button[type="submit"]').click();
    cy.contains('Morning Run', { timeout: 5000 }).should('be.visible');
  });

  it('should mark habit as complete', () => {
    cy.get('.fab').click();
    cy.get('#habit-name').type('Exercise');
    cy.get('#habit-description').type('Daily workout');
    cy.get('button[type="submit"]').click();
    cy.contains('Exercise', { timeout: 5000 }).should('be.visible');

    cy.contains('Exercise')
      .parents('.habit-card')
      .find('.habit-checkbox')
      .click();
  });

  it('should delete a habit', () => {
  cy.get('.fab').click();
  cy.get('#habit-name').type('Delete Me');
  cy.get('#habit-description').type('This will be deleted');
  cy.get('button[type="submit"]').click();
  cy.contains('Delete Me', { timeout: 5000 }).should('be.visible');

  cy.contains('Delete Me')
    .parents('.habit-card')
    .find('.action-btn.delete')
    .click({ force: true });

  // Wait for delete to complete, then verify no habit cards exist
  cy.get('.habit-card', { timeout: 5000 }).should('not.exist');
});

  it('should logout', () => {
    cy.get('.user-avatar').click();
    cy.contains('Logout').click();
    cy.get('h2').contains('Login').should('be.visible');
  });
});