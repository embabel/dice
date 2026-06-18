%% DICE Prolog Inference Rules
%% Domain-Integrated Context Engineering
%%
%% These rules define derived relationships and inference patterns
%% for the DICE knowledge base.

%% =============================================================================
%% Transitive Relationships
%% =============================================================================

% Transitive reporting chain: X reports to Y directly or indirectly
reports_to_chain(X, Y) :- reports_to(X, Y).
reports_to_chain(X, Y) :- reports_to(X, Z), reports_to_chain(Z, Y).

% Transitive management: X manages Y directly or indirectly
manages_chain(X, Y) :- manages(X, Y).
manages_chain(X, Y) :- manages(X, Z), manages_chain(Z, Y).

%% =============================================================================
%% Derived Relationships
%% =============================================================================

% Coworkers: people who work at the same company
coworker(X, Y) :- works_at(X, Company), works_at(Y, Company), X \= Y.

% Teammates: people who are members of the same group/team
teammate(X, Y) :- member_of(X, Team), member_of(Y, Team), X \= Y.

%% =============================================================================
%% Expertise Queries
%% =============================================================================

% Someone can help with a topic if they're an expert in it
can_help_with(Expert, Topic) :- expert_in(Expert, Topic).

% Someone can also help if they know the topic
can_help_with(Person, Topic) :- knows(Person, Topic).

% You can consult a friend who is an expert
can_consult(Person, Expert, Topic) :-
    friend_of(Person, Expert),
    expert_in(Expert, Topic).

% You can consult a colleague who is an expert
can_consult(Person, Expert, Topic) :-
    colleague_of(Person, Expert),
    expert_in(Expert, Topic).

% You can consult a coworker who is an expert
can_consult(Person, Expert, Topic) :-
    coworker(Person, Expert),
    expert_in(Expert, Topic).

%% =============================================================================
%% Location Queries
%% =============================================================================

% People in the same location
same_location(X, Y) :- lives_in(X, Place), lives_in(Y, Place), X \= Y.

%% =============================================================================
%% Ownership Queries
%% =============================================================================

% Check if someone owns something of a certain type
% (requires type facts to be asserted separately)
owner_of_type(Person, Type) :- owns(Person, Thing), type_of(Thing, Type).

%% =============================================================================
%% Social Network Queries
%% =============================================================================

% Friends of friends (2 degrees of separation)
friend_of_friend(X, Y) :- friend_of(X, Z), friend_of(Z, Y), X \= Y.

% Connected through work (colleague or coworker)
work_connection(X, Y) :- colleague_of(X, Y).
work_connection(X, Y) :- coworker(X, Y).
