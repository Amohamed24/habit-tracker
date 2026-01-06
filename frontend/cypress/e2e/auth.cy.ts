describe('Authentication', () => {
  beforeEach(() => {
    cy.visit('/');
  });

  it('should display login form', () => {
    cy.get('h2').contains('Login').should('be.visible');
    cy.get('#email').should('be.visible');
    cy.get('#password').should('be.visible');
    cy.get('button[type="submit"]').contains('Login').should('be.visible');
  });

  it('should switch to register form', () => {
    cy.get('.switch-auth button').click();
    cy.get('h2').contains('Create Account').should('be.visible');
  });

  it('should show error for invalid login', () => {
    cy.get('#email').type('invalid@example.com');
    cy.get('#password').type('wrongpassword');
    cy.get('button[type="submit"]').click();
    cy.get('.error-message').should('be.visible');
  });

  it('should register new user', () => {
    const email = `test${Date.now()}@example.com`;
    
    cy.get('.switch-auth button').click();
    cy.get('#email').type(email);
    cy.get('#password').type('password123');
    cy.get('#confirmPassword').type('password123');
    cy.get('button[type="submit"]').click();
    
    cy.get('h1').contains("Today's Habits", { timeout: 10000 }).should('be.visible');
  });
});